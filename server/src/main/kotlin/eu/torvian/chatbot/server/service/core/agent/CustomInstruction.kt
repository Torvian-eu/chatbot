package eu.torvian.chatbot.server.service.core.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes

/**
 * User-editable free text.
 *
 * @property name Human-readable label of the instruction.
 * @property message The custom instruction text (already populated).
 */
data class CustomInstruction(
    override val name: String,
    override val message: String
) : AgentInstruction {
    override val type: String = AgentInstructionTypes.CUSTOM

    override suspend fun loadMessage() {
        // Message is already populated.
    }
}