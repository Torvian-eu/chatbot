package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.project.ProjectDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Tests for the spawn-target grouping of the agent-role form ([buildSpawnableAgentRoleSections]).
 *
 * The grouping decides the chip order the user sees, so the covered rules are the group order (own scope
 * first, other projects by name, "No project" last), the fallback headings for unresolvable project ids
 * and the in-group name sort.
 */
class SpawnableAgentRoleSectionsTest {

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
    fun `the draft's own project group comes first and other projects follow by name`() {
        val projects = listOf(project(1, "Zeta"), project(2, "Alpha"), project(3, "Beta"))
        val roles = listOf(
            role(10, "own", projectId = 3),
            role(11, "alpha-role", projectId = 2),
            role(12, "zeta-role", projectId = 1)
        )

        val sections = buildSpawnableAgentRoleSections(
            roles = roles,
            projects = projects,
            ownProjectId = 3,
            editedRoleId = null
        )

        assertEquals(listOf("Beta", "Alpha", "Zeta"), sections.map { it.title })
        assertEquals(listOf(3L, 2L, 1L), sections.map { it.projectId })
        assertEquals(listOf(10L), sections[0].roles.map { it.id })
    }

    @Test
    fun `an unassociated draft groups unresolvable and unassociated roles under No project first`() {
        val roles = listOf(
            role(10, "loose"),
            role(12, "orphan", projectId = 99),
            role(11, "project-role", projectId = 1)
        )

        val sections = buildSpawnableAgentRoleSections(
            roles = roles,
            projects = listOf(project(1, "Alpha")),
            ownProjectId = null,
            editedRoleId = null
        )

        // The unassociated group is the draft's own scope, so it leads and absorbs the role whose project
        // no longer resolves instead of repeating the same heading.
        assertEquals(listOf(NO_PROJECT_SECTION_TITLE, "Alpha"), sections.map { it.title })
        assertEquals(listOf(10L, 12L), sections[0].roles.map { it.id })
        assertNull(sections[0].projectId)
        assertEquals(listOf(11L), sections[1].roles.map { it.id })
    }

    @Test
    fun `a project-bound draft trails the No project group with unresolvable project ids`() {
        val roles = listOf(
            role(10, "own", projectId = 1),
            role(11, "loose"),
            role(12, "orphan", projectId = 99)
        )

        val sections = buildSpawnableAgentRoleSections(
            roles = roles,
            projects = listOf(project(1, "Alpha")),
            ownProjectId = 1,
            editedRoleId = null
        )

        assertEquals(listOf("Alpha", NO_PROJECT_SECTION_TITLE), sections.map { it.title })
        assertEquals(listOf(10L), sections[0].roles.map { it.id })
        assertEquals(listOf(11L, 12L), sections[1].roles.map { it.id })
    }

    @Test
    fun `an unresolvable own project keeps its id as the group heading`() {
        val sections = buildSpawnableAgentRoleSections(
            roles = listOf(role(10, "own", projectId = 99), role(11, "loose")),
            projects = emptyList(),
            ownProjectId = 99,
            editedRoleId = null
        )

        assertEquals(listOf("Project #99", NO_PROJECT_SECTION_TITLE), sections.map { it.title })
        assertEquals(99L, sections[0].projectId)
        assertEquals(listOf(10L), sections[0].roles.map { it.id })
        assertEquals(listOf(11L), sections[1].roles.map { it.id })
    }

    @Test
    fun `the edited role joins the first group exactly once when its stored project differs`() {
        val projects = listOf(project(1, "Alpha"), project(2, "Beta"))
        val roles = listOf(
            role(10, "own", projectId = 1),
            role(12, "edited", projectId = 2),
            role(13, "other", projectId = 2)
        )

        val sections = buildSpawnableAgentRoleSections(
            roles = roles,
            projects = projects,
            ownProjectId = 1,
            editedRoleId = 12
        )

        // The draft's scope is what the role will be saved with, so the edited role appears in that group
        // (and only there), not under its currently persisted project.
        assertEquals(listOf("Alpha", "Beta"), sections.map { it.title })
        assertEquals(listOf(12L, 10L), sections[0].roles.map { it.id })
        assertEquals(listOf(13L), sections[1].roles.map { it.id })
        assertEquals(1, sections.sumOf { section -> section.roles.count { it.id == 12L } })
    }

    @Test
    fun `roles are name-sorted inside a group with an id tie-break`() {
        val roles = listOf(
            role(3, "Charlie", projectId = 1),
            role(2, "alpha", projectId = 1),
            role(1, "Alpha", projectId = 1),
            role(4, "bravo", projectId = 1)
        )

        val sections = buildSpawnableAgentRoleSections(
            roles = roles,
            projects = listOf(project(1, "Project")),
            ownProjectId = 1,
            editedRoleId = null
        )

        // Case-insensitive name order; 1 ("Alpha") precedes 2 ("alpha") on the id tie-break.
        assertEquals(listOf(1L, 2L, 4L, 3L), sections.single().roles.map { it.id })
    }

    @Test
    fun `projects without roles and a roleless input produce no groups`() {
        assertEquals(
            emptyList(),
            buildSpawnableAgentRoleSections(
                roles = emptyList(),
                projects = listOf(project(1, "Alpha")),
                ownProjectId = 1,
                editedRoleId = null
            )
        )

        val sections = buildSpawnableAgentRoleSections(
            roles = listOf(role(1, "a", projectId = 1)),
            projects = listOf(project(1, "Alpha"), project(2, "Beta")),
            ownProjectId = 1,
            editedRoleId = null
        )

        assertEquals(listOf("Alpha"), sections.map { it.title })
    }
}
