package eu.torvian.chatbot.server.service.core.agent

/**
 * Summary of a role that may be advertised to the current role as spawnable.
 *
 * Only safe descriptive metadata is retained so model prompts never expose another role's tools,
 * model configuration, or credentials.
 *
 * @property id Stable role identifier; the value the model passes in `agent_role_id`.
 * @property name Machine-readable role name, used as the displayed label when [displayName] is blank.
 * @property displayName Optional human-friendly label, preferred over [name].
 * @property description User-authored explanation.
 * @property projectId Identifier of the single project the target belongs to, or null when the target
 *            is unassociated.
 * @property projectName Name of that project, or null when the target is unassociated or the id no
 *            longer resolves.
 */
data class AgentRoleSummary(
    val id: Long,
    val name: String,
    val displayName: String?,
    val description: String,
    val projectId: Long? = null,
    val projectName: String? = null
) {
    /**
     * Label the prompt shows for this target: [displayName] when it carries text, otherwise [name].
     */
    val displayLabel: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: name
}
