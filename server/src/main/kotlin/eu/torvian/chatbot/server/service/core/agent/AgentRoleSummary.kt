package eu.torvian.chatbot.server.service.core.agent

/**
 * Summary of a role that may be advertised to the current role as spawnable.
 *
 * Only safe descriptive metadata is retained so model prompts never expose another role's tools,
 * model configuration, or credentials.
 *
 * @property id Stable role identifier, retained for diagnostics and future UI use.
 * @property name Machine-readable role name accepted by `spawn_agent`.
 * @property displayName Optional human-friendly label.
 * @property description User-authored explanation, rendered with a bounded length.
 */
data class AgentRoleSummary(
    val id: Long,
    val name: String,
    val displayName: String?,
    val description: String
)