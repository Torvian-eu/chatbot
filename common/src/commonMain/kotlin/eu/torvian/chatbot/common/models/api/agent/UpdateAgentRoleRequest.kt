package eu.torvian.chatbot.common.models.api.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import kotlinx.serialization.Serializable

/**
 * Request body for updating an existing user-defined agent role.
 *
 * Mirrors [CreateAgentRoleRequest]: all configuration fields are present and are replaced wholesale on
 * update (the role's configuration is rewritten, so the update is a full replacement, not a patch).
 *
 * The role's LLM configuration is supplied exclusively through [modelPresetId], which references a
 * user-owned [eu.torvian.chatbot.common.models.llm.ModelPresetDto]. Setting it attaches a preset and
 * setting it to null detaches the current one, either way leaving the role **non-sendable** until a
 * usable preset is attached. The preset is optional, which lets callers (e.g. the server built-in
 * `update_agent_role` tool) preserve a preset-less role or complete a role that was created without
 * one.
 *
 * @property name Unique (per user) machine-readable role name, non-blank and at most 255 characters.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the role.
 * @property modelPresetId Identifier of the model preset holding the role's model and settings
 *            profile, or null to leave the role preset-less. The value replaces the previous
 *            reference wholesale (a full replacement). The preset must be owned by the requesting
 *            user; a preset whose model/settings reference is null may still be attached — the role
 *            simply cannot drive a turn until it is repaired.
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
    val modelPresetId: Long? = null,
    val toolIds: Set<Long> = emptySet(),
    val spawnableAgentRoleIds: Set<Long> = emptySet(),
    val instructions: List<AgentInstructionDto> = emptyList(),
    val projectId: Long? = null
)
