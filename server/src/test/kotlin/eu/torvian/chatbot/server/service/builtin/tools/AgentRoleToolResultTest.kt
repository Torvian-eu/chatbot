package eu.torvian.chatbot.server.service.builtin.tools

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit tests for the operation summaries returned by the mutating agent-role tools
 * ([formatCreatedAgentRole], [formatUpdatedAgentRole], [formatDeletedAgentRole]).
 *
 * Covers the concise non-JSON format (the action phrase plus the role identity), the deletion
 * summary's sweep notes (removed and still-linked instruction ids), and the guarantee that no full
 * role payload leaks into the output.
 */
class AgentRoleToolResultTest {

    private fun sampleRole() = AgentRoleDto(
        id = 1L,
        name = "writer",
        displayName = "Writer",
        description = "Writes code",
        modelId = 3L,
        modelSettingsId = 4L,
        tools = setOf(6L, 5L),
        spawnableAgentRoleIds = setOf(2L),
        instructions = listOf(
            AgentInstructionDto(id = 10L, type = AgentInstructionTypes.ROLE, name = "Role", message = "You are a writer."),
            AgentInstructionDto(id = 11L, type = AgentInstructionTypes.CUSTOM, name = "Style", message = "Be concise.")
        )
    )

    @Test
    fun `formats the created operation summary`() {
        val summary = formatCreatedAgentRole(sampleRole())

        assertEquals("Created agent role 'writer' (id: 1).", summary, "unexpected: $summary")
    }

    @Test
    fun `formats the updated operation summary`() {
        val summary = formatUpdatedAgentRole(sampleRole())

        assertEquals("Updated agent role 'writer' (id: 1).", summary, "unexpected: $summary")
    }

    @Test
    fun `formats the deleted operation summary`() {
        val summary = formatDeletedAgentRole(7L)

        assertEquals("Deleted agent role (id: 7).", summary, "unexpected: $summary")
    }

    @Test
    fun `deleted summary names removed and still-linked instructions`() {
        val summary = formatDeletedAgentRole(
            roleId = 7L,
            deletedInstructionIds = listOf(3L, 5L),
            retainedInstructionIds = listOf(9L)
        )

        assertEquals(
            "Deleted agent role (id: 7); removed 2 instruction(s) that lost their last link " +
                "(ids: 3, 5); kept 1 instruction(s) still linked by other role(s) (ids: 9).",
            summary,
            "unexpected: $summary"
        )
    }

    @Test
    fun `deleted summary omits empty sweep clauses`() {
        val deletedOnly = formatDeletedAgentRole(roleId = 7L, deletedInstructionIds = listOf(3L))
        val keptOnly = formatDeletedAgentRole(roleId = 7L, retainedInstructionIds = listOf(9L))

        assertEquals(
            "Deleted agent role (id: 7); removed 1 instruction(s) that lost their last link (ids: 3).",
            deletedOnly,
            "unexpected: $deletedOnly"
        )
        assertEquals(
            "Deleted agent role (id: 7); kept 1 instruction(s) still linked by other role(s) (ids: 9).",
            keptOnly,
            "unexpected: $keptOnly"
        )
    }

    @Test
    fun `summaries are plain text never JSON and never echo role fields`() {
        val role = sampleRole()
        val summaries = listOf(
            formatCreatedAgentRole(role),
            formatUpdatedAgentRole(role),
            formatDeletedAgentRole(role.id)
        )

        summaries.forEach { summary ->
            assertFalse(summary.contains("{"), "unexpected JSON in: $summary")
            assertFalse(summary.contains("}"), "unexpected JSON in: $summary")
            assertFalse(summary.contains("\"instructions\""), "unexpected JSON in: $summary")
            assertFalse(summary.contains("You are a writer."), "instruction text leaked in: $summary")
            assertFalse(summary.contains("Writes code"), "role description leaked in: $summary")
        }
    }
}
