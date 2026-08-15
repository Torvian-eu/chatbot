package eu.torvian.chatbot.server.service.core.agent

/**
 * Server-side domain model of a user-defined agent role — the source of truth inside the service layer.
 *
 * This is derived from (and maps back to) the wire/storage shape [eu.torvian.chatbot.common.models.agent.AgentRoleDto]
 * at the service boundary. The DTOs carry [eu.torvian.chatbot.common.models.agent.AgentInstructionDto]
 * entries whose messages are always resolved; this domain type carries [AgentInstruction] objects whose
 * messages are resolved via [AgentInstruction.loadMessage] by server components (role reads, turn
 * preparation).
 *
 * @property id Immutable, database-generated identifier.
 * @property name Unique (per user) machine-readable role name.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the role's purpose.
 * @property modelId Identifier of the LLM model used by the role; null after the model is deleted.
 * @property modelSettingsId Identifier of the settings profile (CHAT/RESPONSES) used by the role; null
 *            after the settings are deleted.
 * @property tools Set of tool-definition identifiers attached to the role. Unordered; duplicates are
 *            impossible (the `agent_role_tools` primary key and the wire `Set` both reject them).
 * @property instructions Domain instruction objects composing the role's system prompt.
 */
data class AgentRole(
    val id: Long,
    val name: String,
    val displayName: String? = null,
    val description: String = "",
    val modelId: Long?,
    val modelSettingsId: Long?,
    val tools: Set<Long> = emptySet(),
    val instructions: List<AgentInstruction> = emptyList()
)
