package eu.torvian.chatbot.server.service.core.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes

/**
 * Static role description, e.g. "You are a senior software architect...".
 *
 * @property name Human-readable label of the instruction.
 * @property message The role description text (already populated).
 */
data class RoleInstruction(
    override val name: String,
    override val message: String
) : AgentInstruction {
    override val type: String = AgentInstructionTypes.ROLE

    override suspend fun loadMessage() {
        // Message is already populated.
    }
}