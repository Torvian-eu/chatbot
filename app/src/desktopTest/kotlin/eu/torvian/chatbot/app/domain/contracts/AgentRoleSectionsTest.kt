package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.project.ProjectDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Tests for the Settings → Agent Roles grouping/filter derivation ([buildAgentRoleSections] and
 * [filterAgentRoleSections]).
 */
class AgentRoleSectionsTest {

    private fun project(id: Long, name: String) = ProjectDto(
        id = id,
        name = name,
        description = "",
        createdAt = Instant.fromEpochSeconds(id),
        agentRoleIds = emptySet()
    )

    private fun role(id: Long, name: String, projectId: Long? = null) = AgentRoleDto(
        id = id,
        name = name,
        displayName = null,
        description = "",
        modelId = 1L,
        modelSettingsId = 2L,
        tools = emptySet(),
        instructions = emptyList(),
        projectId = projectId
    )

    @Test
    fun `sections - orders projects by name and places No project last`() {
        val projects = listOf(project(1, "Zeta"), project(2, "Alpha"))
        val roles = listOf(
            role(10, "zeta-role", projectId = 1),
            role(11, "loose", projectId = null),
            role(12, "alpha-role", projectId = 2)
        )

        val sections = buildAgentRoleSections(roles, projects)

        assertEquals(3, sections.size)
        // Projects first, sorted by name; the unassociated role trails in "No project".
        assertEquals(listOf("Alpha", "Zeta", NO_PROJECT_SECTION_TITLE), sections.map { it.title })
        assertEquals(listOf(12L), sections[0].roles.map { it.id })
        assertEquals(listOf(10L), sections[1].roles.map { it.id })
        assertEquals(listOf(11L), sections[2].roles.map { it.id })
        assertNull(sections[2].projectId)
    }

    @Test
    fun `sections - keeps repository order inside a section`() {
        val projects = listOf(project(1, "Research"))
        val roles = listOf(
            role(3, "c", projectId = 1),
            role(1, "a", projectId = 1),
            role(2, "b", projectId = 1)
        )

        val sections = buildAgentRoleSections(roles, projects)

        assertEquals(1, sections.size)
        // The repository order (3, 1, 2) is preserved; no re-sorting by role name.
        assertEquals(listOf(3L, 1L, 2L), sections.single().roles.map { it.id })
    }

    @Test
    fun `sections - no projects puts every role under No project`() {
        val roles = listOf(
            role(1, "a", projectId = null),
            role(2, "b", projectId = null)
        )

        val sections = buildAgentRoleSections(roles, emptyList())

        assertEquals(1, sections.size)
        assertEquals(NO_PROJECT_SECTION_TITLE, sections.single().title)
        assertEquals(listOf(1L, 2L), sections.single().roles.map { it.id })
    }

    @Test
    fun `sections - roles referencing an unknown project fall under No project`() {
        // A project was deleted server-side but the role stream still carries the stale id: the role
        // must not be dropped and must not render under a dead header.
        val sections = buildAgentRoleSections(
            roles = listOf(role(1, "orphan", projectId = 99L)),
            projects = emptyList()
        )

        assertEquals(1, sections.size)
        assertEquals(NO_PROJECT_SECTION_TITLE, sections.single().title)
        assertEquals(listOf(1L), sections.single().roles.map { it.id })
    }

    @Test
    fun `sections - empty projects are omitted`() {
        val projects = listOf(project(1, "Empty"), project(2, "Used"))
        val roles = listOf(role(1, "used", projectId = 2))

        val sections = buildAgentRoleSections(roles, projects)

        assertEquals(listOf("Used"), sections.map { it.title })
    }

    @Test
    fun `sections - no roles produces no sections`() {
        assertEquals(emptyList(), buildAgentRoleSections(emptyList(), listOf(project(1, "P"))))
    }

    @Test
    fun `filter - all projects keeps every section`() {
        val sections = listOf(
            AgentRoleSection("Alpha", 1, listOf(role(1, "a", projectId = 1))),
            AgentRoleSection(NO_PROJECT_SECTION_TITLE, null, listOf(role(2, "b")))
        )

        assertEquals(sections, filterAgentRoleSections(sections, AgentRoleFilter.AllProjects))
    }

    @Test
    fun `filter - project scope keeps only that project's section`() {
        val sections = listOf(
            AgentRoleSection("Alpha", 1, listOf(role(1, "a", projectId = 1))),
            AgentRoleSection("Beta", 2, listOf(role(2, "b", projectId = 2)))
        )

        assertEquals(listOf(sections[0]), filterAgentRoleSections(sections, AgentRoleFilter.Project(1)))
    }

    @Test
    fun `filter - no project keeps only the unassociated section`() {
        val sections = listOf(
            AgentRoleSection("Alpha", 1, listOf(role(1, "a", projectId = 1))),
            AgentRoleSection(NO_PROJECT_SECTION_TITLE, null, listOf(role(2, "b")))
        )

        assertEquals(listOf(sections[1]), filterAgentRoleSections(sections, AgentRoleFilter.NoProject))
    }

    @Test
    fun `filter - scope without roles yields no sections`() {
        val sections = listOf(
            AgentRoleSection("Alpha", 1, listOf(role(1, "a", projectId = 1)))
        )

        assertEquals(emptyList(), filterAgentRoleSections(sections, AgentRoleFilter.Project(2)))
    }
}
