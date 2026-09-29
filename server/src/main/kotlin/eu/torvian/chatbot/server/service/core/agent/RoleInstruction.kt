package eu.torvian.chatbot.server.service.core.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes

/**
 * Static role description, e.g. "You are a senior software architect...".
 *
 * @property id Identifier of the stored instruction row the value was read from.
 * @property name Human-readable label of the instruction.
 * @property message The role description text (already populated).
 * @property linkedRoleIds Ids of the agent roles that link that row.
 */
data class RoleInstruction(
    override val id: Long,
    override val name: String,
    override val message: String,
    override val linkedRoleIds: Set<Long> = emptySet()
) : AgentInstruction {
    override val type: String = AgentInstructionTypes.ROLE

    override suspend fun loadMessage() {
        // Message is already populated.
    }
}