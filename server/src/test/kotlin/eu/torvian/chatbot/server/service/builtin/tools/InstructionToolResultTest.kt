package eu.torvian.chatbot.server.service.builtin.tools

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [formatDeletedInstruction].
 *
 * Only a row no role links is deletable, so the summary has a single form naming the deleted row.
 */
class InstructionToolResultTest {

    /**
     * Creates the deleted-row fixture.
     *
     * @param linkedRoleIds Ids of the roles the row reported before the delete.
     * @return An instruction row suitable for summary assertions.
     */
    private fun instruction(linkedRoleIds: Set<Long>) = AgentInstructionDto(
        id = 7L,
        type = AgentInstructionTypes.MAIN,
        name = "Project rules",
        message = "Follow the project rules.",
        linkedRoleIds = linkedRoleIds
    )

    /**
     * Verifies that the summary names the row's label and id and nothing else.
     */
    @Test
    fun `summarizes the deleted row by name and id`() {
        val summary = formatDeletedInstruction(instruction(emptySet()))

        assertEquals("Deleted instruction 'Project rules' (id: 7).", summary)
    }

    /**
     * Verifies that no wording mentions roles, because a linked row is never deleted.
     */
    @Test
    fun `never reports roles`() {
        val summary = formatDeletedInstruction(instruction(emptySet()))

        assertTrue(!summary.contains("role"))
    }
}