package eu.torvian.chatbot.server.service.core.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes

/**
 * Pure server-side domain type for a single agent-role instruction.
 *
 * This interface is deliberately NOT `@Serializable` / `@Polymorphic`: the only shape that crosses the
 * wire or is persisted is the flat [eu.torvian.chatbot.common.models.agent.AgentInstructionDto]. This
 * interface exists purely for behavior — namely [loadMessage], which resolves the instruction's message
 * from its source (the database for DB-backed variants like [SpawnableAgentsInstruction]).
 *
 * @property type Well-known [AgentInstructionTypes] key that drives DTO mapping.
 * @property name Human-readable label of the instruction.
 * @property message Resolved instruction text. May be empty until [loadMessage] has been invoked.
 */
interface AgentInstruction {
    /** Instruction kind; drives the DTO mapping. */
    val type: String

    /** Human-readable label of the instruction. */
    val name: String

    /** Resolved instruction text. */
    val message: String

    /**
     * Loads the instruction's message from its source into [message].
     *
     * Static instruction kinds ([RoleInstruction], [MainInstruction], [CustomInstruction]) already
     * carry their message, so this is a no-op for them. [SpawnableAgentsInstruction] generates its
     * message from ownership-scoped metadata on first load.
     */
    suspend fun loadMessage()
}
