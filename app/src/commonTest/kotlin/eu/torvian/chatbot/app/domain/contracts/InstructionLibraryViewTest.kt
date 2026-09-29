package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.agent.shared
import eu.torvian.chatbot.common.models.project.ProjectDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for the instruction library view contracts: the three selection modes, the project-qualified
 * usage resolution that labels the roles linking a row, the project scope predicate and the assignment
 * targets.
 *
 * The library is the unfiltered repository listing, so every mode and scope is a pure narrowing of the
 * delivered rows and needs no request of its own.
 */
class InstructionLibraryViewTest {

    private fun instruction(
        id: Long,
        name: String,
        linkedRoleIds: Set<Long> = emptySet(),
        type: String = AgentInstructionTypes.CUSTOM,
        message: String = "Be concise"
    ) = AgentInstructionDto(
        id = id,
        type = type,
        name = name,
        message = message,
        linkedRoleIds = linkedRoleIds
    )

    private fun role(id: Long, name: String, displayName: String? = null, projectId: Long? = null) = AgentRoleDto(
        id = id,
        name = name,
        displayName = displayName,
        modelId = 1L,
        modelSettingsId = 2L,
        projectId = projectId
    )

    private fun project(id: Long, name: String) = ProjectDto(
        id = id,
        name = name,
        description = "",
        createdAt = Instant.fromEpochSeconds(id),
        agentRoleIds = emptySet()
    )

    /** The library used by the selection tests: one shared row, one role-specific row, one orphan. */
    private val library = listOf(
        instruction(1L, "Tone", linkedRoleIds = setOf(2L, 7L)),
        instruction(2L, "Style", linkedRoleIds = setOf(7L)),
        instruction(3L, "Available agents", type = AgentInstructionTypes.SPAWNABLE_AGENTS, message = "")
    )

    @Test
    fun `All keeps every row of the library`() {
        assertEquals(listOf(1L, 2L, 3L), library.filterBy(InstructionLibraryFilter.All).map { it.id })
    }

    @Test
    fun `ByRole keeps only the rows that role links`() {
        // A role-specific selection lists its rows and not the rows shared with other roles.
        assertEquals(listOf(1L), library.filterBy(InstructionLibraryFilter.ByRole(2L)).map { it.id })
        assertEquals(listOf(1L, 2L), library.filterBy(InstructionLibraryFilter.ByRole(7L)).map { it.id })
        assertEquals(emptyList(), library.filterBy(InstructionLibraryFilter.ByRole(99L)).map { it.id })
    }

    @Test
    fun `Unassigned keeps only the rows no role links`() {
        // Dropping the last link keeps the row in the library, which is exactly what this mode lists.
        assertEquals(listOf(3L), library.filterBy(InstructionLibraryFilter.Unassigned).map { it.id })
    }

    @Test
    fun `usage labels the linking roles with their project`() {
        val projectsById = mapOf(1L to project(1L, "Marketing"))
        val rolesById = mapOf(
            2L to role(2L, "writer", displayName = "Writer", projectId = 1L),
            7L to role(7L, "coder")
        )

        val usage = library.first().usage(rolesById, projectsById)

        // Ascending by role id; the display name wins over the machine name and the project qualifies
        // the label so identically named roles stay distinguishable.
        assertEquals(
            listOf(2L to "Writer — Marketing", 7L to "coder — No project"),
            usage.map { it.roleId to it.label }
        )
    }

    @Test
    fun `usage keeps an unresolvable role visible with its id`() {
        // A stale or partial catalog must not hide a link that exists on the server.
        assertEquals(
            listOf(2L to "Role #2 — No project", 7L to "Role #7 — No project"),
            library.first().usage(emptyMap(), emptyMap()).map { it.roleId to it.label }
        )
    }

    @Test
    fun `roleWithProjectLabel qualifies the label with the project name`() {
        val projectsById = mapOf(1L to project(1L, "Marketing"))

        assertEquals(
            "Writer — Marketing",
            role(2L, "writer", displayName = "Writer", projectId = 1L).roleWithProjectLabel(projectsById)
        )
        assertEquals("coder — No project", role(7L, "coder").roleWithProjectLabel(projectsById))
    }

    @Test
    fun `roleWithProjectLabel treats an unresolvable project id as no project`() {
        val projectsById = mapOf(1L to project(1L, "Marketing"))

        // The role points at a project the catalog does not hold, so the label degrades instead of
        // inventing an attribution.
        assertEquals(
            "writer — No project",
            role(2L, "writer", projectId = 99L).roleWithProjectLabel(projectsById)
        )
    }

    @Test
    fun `roleWithProjectLabel keeps the qualifier with a single project and a single link`() {
        val projectsById = mapOf(1L to project(1L, "Marketing"))

        // The project component is never suppressed, so the format is stable however small the data is.
        assertTrue(
            role(2L, "writer", displayName = "Writer", projectId = 1L)
                .roleWithProjectLabel(projectsById)
                .contains(" — ")
        )
    }

    @Test
    fun `roleUsageLabel resolves a role and falls back to the id for an unknown one`() {
        val projectsById = mapOf(1L to project(1L, "Marketing"))
        val rolesById = mapOf(2L to role(2L, "writer", displayName = "Writer", projectId = 1L))

        assertEquals("Writer — Marketing", roleUsageLabel(2L, rolesById, projectsById))
        assertEquals("Role #99 — No project", roleUsageLabel(99L, rolesById, projectsById))
    }

    @Test
    fun `a degraded project catalog labels every role as no project`() {
        val rolesById = mapOf(2L to role(2L, "writer", displayName = "Writer", projectId = 1L))
        val linked = instruction(1L, "Tone", linkedRoleIds = setOf(2L))

        assertEquals("Writer — No project", roleUsageLabel(2L, rolesById, emptyMap()))
        // With no catalog a link can only be attributed to "No project", and the row is never hidden.
        assertTrue(linked.matchesProjectScope(AgentRoleFilter.NoProject, rolesById, emptyMap()))
    }

    @Test
    fun `matchesProjectScope keeps rows whose linked role falls in the project`() {
        val projectsById = mapOf(1L to project(1L, "Marketing"), 2L to project(2L, "Engineering"))
        val rolesById = mapOf(
            2L to role(2L, "writer", projectId = 1L),
            7L to role(7L, "coder", projectId = 2L),
            9L to role(9L, "reviewer")
        )
        val rows = listOf(
            instruction(10L, "Marketing tone", linkedRoleIds = setOf(2L)),
            instruction(20L, "Engineering style", linkedRoleIds = setOf(7L)),
            instruction(30L, "Unassociated", linkedRoleIds = setOf(9L)),
            instruction(40L, "Deleted role", linkedRoleIds = setOf(5L)),
            instruction(50L, "Unassigned")
        )

        assertEquals(
            listOf(10L, 20L, 30L, 40L, 50L),
            rows.filterByProject(AgentRoleFilter.AllProjects, rolesById, projectsById).map { it.id }
        )
        assertEquals(
            listOf(10L),
            rows.filterByProject(AgentRoleFilter.Project(1L), rolesById, projectsById).map { it.id }
        )
        assertEquals(
            listOf(20L),
            rows.filterByProject(AgentRoleFilter.Project(2L), rolesById, projectsById).map { it.id }
        )
        // An unassociated role and a link whose role left the catalog both count as "No project".
        assertEquals(
            listOf(30L, 40L),
            rows.filterByProject(AgentRoleFilter.NoProject, rolesById, projectsById).map { it.id }
        )
    }

    @Test
    fun `an unassigned row matches only the all-projects scope`() {
        val unassigned = instruction(50L, "Unassigned")

        assertTrue(unassigned.matchesProjectScope(AgentRoleFilter.AllProjects, emptyMap(), emptyMap()))
        assertFalse(unassigned.matchesProjectScope(AgentRoleFilter.Project(1L), emptyMap(), emptyMap()))
        assertFalse(unassigned.matchesProjectScope(AgentRoleFilter.NoProject, emptyMap(), emptyMap()))
    }

    @Test
    fun `assignableRoles excludes the roles that already link the row`() {
        val roles = listOf(role(2L, "writer"), role(7L, "coder"), role(9L, "reviewer"))

        val assignable = library.first().assignableRoles(roles, emptyMap())

        assertEquals(listOf(9L), assignable.map { it.id })
    }

    @Test
    fun `assignableRoles orders the remaining roles by label`() {
        val roles = listOf(
            role(9L, "zeta"),
            role(8L, "Alpha", displayName = "alpha"),
            role(10L, "beta")
        )

        assertEquals(
            listOf(8L, 10L, 9L),
            instruction(1L, "Tone").assignableRoles(roles, emptyMap()).map { it.id }
        )
    }

    @Test
    fun `assignableRoles orders the remaining roles by project then label`() {
        val projectsById = mapOf(1L to project(1L, "Marketing"), 2L to project(2L, "Engineering"))
        val roles = listOf(
            role(9L, "zeta", projectId = 2L),
            role(8L, "alpha", projectId = 1L),
            role(10L, "beta")
        )

        // Engineering sorts before Marketing, and the unassociated role trails both.
        assertEquals(
            listOf(9L, 8L, 10L),
            instruction(1L, "Tone").assignableRoles(roles, projectsById).map { it.id }
        )
    }

    @Test
    fun `filter options offer all, unassigned and one entry per role in label order`() {
        val roles = listOf(role(9L, "zeta"), role(8L, "alpha"))

        val options = instructionFilterOptions(roles, AgentRoleFilter.AllProjects, emptyMap())

        assertEquals(
            listOf(
                InstructionLibraryFilter.All,
                InstructionLibraryFilter.Unassigned,
                InstructionLibraryFilter.ByRole(8L),
                InstructionLibraryFilter.ByRole(9L)
            ),
            options
        )
    }

    @Test
    fun `filter options narrow the role entries to the project scope`() {
        val projectsById = mapOf(1L to project(1L, "Marketing"), 2L to project(2L, "Engineering"))
        val roles = listOf(
            role(2L, "writer", projectId = 1L),
            role(7L, "coder", projectId = 2L),
            role(9L, "reviewer")
        )

        // Engineering sorts before Marketing; the unassociated role trails both.
        assertEquals(
            listOf(
                InstructionLibraryFilter.All,
                InstructionLibraryFilter.Unassigned,
                InstructionLibraryFilter.ByRole(7L),
                InstructionLibraryFilter.ByRole(2L),
                InstructionLibraryFilter.ByRole(9L)
            ),
            instructionFilterOptions(roles, AgentRoleFilter.AllProjects, projectsById)
        )
        // An unassigned row can never match a concrete scope, so the entry is withheld there.
        assertEquals(
            listOf(InstructionLibraryFilter.All, InstructionLibraryFilter.ByRole(2L)),
            instructionFilterOptions(roles, AgentRoleFilter.Project(1L), projectsById)
        )
        assertEquals(
            listOf(InstructionLibraryFilter.All, InstructionLibraryFilter.ByRole(9L)),
            instructionFilterOptions(roles, AgentRoleFilter.NoProject, projectsById)
        )
    }

    @Test
    fun `filter option labels name the mode and the project-qualified role`() {
        val rolesById = mapOf(8L to role(8L, "alpha"))

        assertEquals("All agent roles", InstructionLibraryFilter.All.optionLabel(rolesById, emptyMap()))
        assertEquals("Unassigned (no role)", InstructionLibraryFilter.Unassigned.optionLabel(rolesById, emptyMap()))
        assertEquals("alpha — No project", InstructionLibraryFilter.ByRole(8L).optionLabel(rolesById, emptyMap()))
        // An unknown role id still names itself instead of rendering an empty control.
        assertEquals("Role #99 — No project", InstructionLibraryFilter.ByRole(99L).optionLabel(rolesById, emptyMap()))
    }

    @Test
    fun `filter option labels drop the project qualifier inside a project scope`() {
        val rolesById = mapOf(8L to role(8L, "alpha"))

        // The scope names the project for every entry, so the role entries do not repeat it.
        assertEquals("alpha", InstructionLibraryFilter.ByRole(8L).optionLabel(rolesById, emptyMap(), includeProject = false))
        assertEquals("Role #99", InstructionLibraryFilter.ByRole(99L).optionLabel(rolesById, emptyMap(), includeProject = false))
    }

    @Test
    fun `same-named roles in two projects produce two distinguishable labels`() {
        val projectsById = mapOf(1L to project(1L, "Marketing"), 2L to project(2L, "Engineering"))
        val roles = listOf(
            role(2L, "writer", displayName = "Writer", projectId = 1L),
            role(3L, "writer", displayName = "Writer", projectId = 2L)
        )
        val rolesById = roles.associateBy { it.id }

        val options = instructionFilterOptions(roles, AgentRoleFilter.AllProjects, projectsById)
            .filterIsInstance<InstructionLibraryFilter.ByRole>()

        assertEquals(2, options.size)
        assertEquals("Writer — Engineering", options[0].optionLabel(rolesById, projectsById))
        assertEquals("Writer — Marketing", options[1].optionLabel(rolesById, projectsById))
    }

    @Test
    fun `the shared marker derives from the linking role count`() {
        // Shared content is reported by the number of linking roles, never by a stored flag.
        assertTrue(library.first().shared)
        assertFalse(library[1].shared)
        assertFalse(library[2].shared)
    }
}