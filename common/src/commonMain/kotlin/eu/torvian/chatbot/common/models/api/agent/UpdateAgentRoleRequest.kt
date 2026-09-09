package eu.torvian.chatbot.common.models.api.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import kotlinx.serialization.Serializable

/**
 * Request body for updating an existing user-defined agent role.
 *
 * Mirrors [CreateAgentRoleRequest]: all configuration fields are present and are replaced wholesale on
 * update (the role's configuration is rewritten, so the update is a full replacement, not a patch).
 * `modelId`/`modelSettingsId` may be null, which lets callers (e.g. the server built-in
 * `update_agent_role` tool) preserve a role that has no model/settings or complete a role that was
 * created without them.
 *
 * @property name Unique (per user) machine-readable role name, non-blank and at most 255 characters.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the role.
 * @property modelId Optional identifier of the LLM model the role uses; null means the role has no
 *            model and is non-sendable until set via update.
 * @property modelSettingsId Optional identifier of the settings profile (CHAT or RESPONSES) the role
 *            uses; null means the role has no settings and is non-sendable until set via update.
 *            When both are provided, the settings must belong to [modelId] and be chat-capable.
 * @property toolIds Set of tool-definition identifiers to attach to the role. Duplicates are
 *            impossible at the wire level (a set), so no service-side de-duplication is needed.
 * @property spawnableAgentRoleIds Same-user role identifiers that this role may spawn. Duplicates are
 *            impossible at the wire level (a set); self-referencing identifiers are allowed. Every
 *            target must belong to the same project scope as the role ([projectId]): the server
 *            rejects a target bound to a different project or a project-less role targeting an
 *            in-project role (and vice versa).
 * @property instructions Flat instruction list (see [AgentInstructionDto]). `model_specific`
 *            entries are multi-instance and each must reference a distinct model. Server-generated
 *            markers such as `spawnable_agents` are carried through as-is; their messages are
 *            re-resolved on every read.
 * @property projectId Identifier of the single user-owned project the role belongs to, or `null` for
 *            an **unassociated** role. A role belongs to at most one project (see
 *            [CreateAgentRoleRequest.projectId]); the value is replaced wholesale on update: setting
 *            a project moves the role there, setting `null` unassociates it.
 *            `spawnableAgentRoleIds` targets must belong to the same project scope as this value.
 *            The id (when non-null) must reference a project owned by the requesting user; a missing
 *            or foreign id is rejected as not-found by the server. The default keeps payloads
 *            produced before this property existed decoding as unassociated.
 */
@Serializable
data class UpdateAgentRoleRequest(
    val name: String,
    val displayName: String? = null,
    val description: String = "",
    val modelId: Long? = null,
    val modelSettingsId: Long? = null,
    val toolIds: Set<Long> = emptySet(),
    val spawnableAgentRoleIds: Set<Long> = emptySet(),
    val instructions: List<AgentInstructionDto> = emptyList(),
    val projectId: Long? = null
)
