package eu.torvian.chatbot.server.service.builtin.tools

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit tests for the operation summaries returned by the mutating agent-role tools
 * ([formatCreatedAgentRole], [formatUpdatedAgentRole], [formatInsertedInstruction],
 * [formatRemovedInstruction]).
 *
 * Covers the concise non-JSON format: the action phrase, the role identity (name and id), the
 * instruction type/name and position for the insert/remove tools, and the guarantee that no full
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
            AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a writer."),
            AgentInstructionDto(AgentInstructionTypes.CUSTOM, "Style", "Be concise.")
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
    fun `formats the inserted instruction summary with type name and position`() {
        val role = sampleRole()
        val summary = formatInsertedInstruction(role, 1, role.instructions[0])

        assertEquals(
            "Inserted instruction (type=role, name=Role) at 0-based position 1 " +
                    "in agent role 'writer' (id: 1).", summary, "unexpected: $summary"
        )
    }

    @Test
    fun `formats the removed instruction summary with type name and position`() {
        val role = sampleRole()
        val summary = formatRemovedInstruction(role, 0, role.instructions[1])

        assertEquals(
            "Removed instruction (type=custom, name=Style) at 0-based position 0 " +
                    "from agent role 'writer' (id: 1).", summary, "unexpected: $summary"
        )
    }

    @Test
    fun `summaries are plain text never JSON and never echo role fields`() {
        val role = sampleRole()
        val summaries = listOf(
            formatCreatedAgentRole(role),
            formatUpdatedAgentRole(role),
            formatInsertedInstruction(role, 0, role.instructions[0]),
            formatRemovedInstruction(role, 0, role.instructions[0])
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
