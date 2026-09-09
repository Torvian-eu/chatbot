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
 * @property spawnableAgentRoleIds Unordered same-user role identifiers this role may spawn; may
 *            include the role's own id (self-spawn). Every target must belong to the **same project
 *            scope** as this role: the same [projectId], or both unassociated. The server validates
 *            this on create/update.
 * @property projectId Single user-owned project identifier the role belongs to, or `null` for the
 *            **unassociated** scope. The relation is deliberately one-column (a role belongs to at
 *            most one project) so same-project rules — the spawn allow-list targets, the
 *            role-name-uniqueness scope, and the Session Legality Invariant — are exact
 *            comparisons: an unassociated role is offered only for project-less sessions, and
 *            same-named roles of the same user conflict only when their scopes are identical.
 * @property instructions Domain instruction objects composing the role's system prompt.
 * @property disabled Whether the role is disabled **for the requesting/acting user** (not globally
 *            and not for the owner in a future shared-role stage). Derived from the per-user
 *            `agent_role_disabled` side table: an absent row means enabled, a present row means
 *            disabled. Rides the domain role so turn-time enforcement needs no extra lookup.
 */
data class AgentRole(
    val id: Long,
    val name: String,
    val displayName: String? = null,
    val description: String = "",
    val modelId: Long?,
    val modelSettingsId: Long?,
    val tools: Set<Long> = emptySet(),
    val spawnableAgentRoleIds: Set<Long> = emptySet(),
    val projectId: Long? = null,
    val instructions: List<AgentInstruction> = emptyList(),
    val disabled: Boolean = false
)
