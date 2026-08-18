package eu.torvian.chatbot.server.service.core.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes

/**
 * Project context, usually `AGENTS.md` content.
 *
 * @property name Human-readable label of the instruction.
 * @property message The project context text (already populated).
 */
data class MainInstruction(
    override val name: String,
    override val message: String
) : AgentInstruction {
    override val type: String = AgentInstructionTypes.MAIN

    override suspend fun loadMessage() {
        // Message is already populated.
    }
}