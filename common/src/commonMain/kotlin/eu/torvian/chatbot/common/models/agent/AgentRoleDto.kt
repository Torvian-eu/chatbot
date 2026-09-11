package eu.torvian.chatbot.common.models.agent

import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import kotlinx.serialization.Serializable

/**
 * Shared, serializable representation of a user-defined agent role.
 *
 * An agent role bundles everything an LLM-powered conversation needs — model, settings, tools and a
 * composed system prompt — into one reusable, user-managed entity. A chat session references an agent
 * role by [id] instead of storing model/settings/tools directly.
 *
 * The role's LLM configuration lives exclusively in the referenced
 * [eu.torvian.chatbot.common.models.llm.ModelPresetDto] ([modelPresetId]): the preset is the sole
 * source of truth, and re-pointing it switches every role bound to it at once. [modelId] and
 * [modelSettingsId] are **derived, read-only convenience values** resolved from that preset — they are
 * never accepted on write and never stored on the role row.
 *
 * @property id Immutable, database-generated identifier.
 * @property name Unique (per user) machine-readable name of the role.
 * @property displayName Optional human-friendly display name; clients fall back to [name].
 * @property description Free-form description of the role's purpose.
 * @property modelId **Derived** identifier of the [LLMModel] the role uses: the referenced preset's
 *            model. Null when no preset is attached, or when the preset's model reference is null
 *            (its model was deleted via `ON DELETE SET NULL`, or the preset never had one). The role
 *            is then non-sendable.
 * @property modelSettingsId **Derived** identifier of the [ModelSettings] profile (CHAT or RESPONSES)
 *            the role uses: the referenced preset's settings profile. Null when no preset is attached,
 *            or when the preset's settings reference is null. The role is then non-sendable.
 * @property modelPresetId Identifier of the [eu.torvian.chatbot.common.models.llm.ModelPresetDto]
 *            holding this role's model/settings configuration, or `null` for a **preset-less** role.
 *            A preset-less role is legal but non-sendable: turn preparation fails loudly until a
 *            preset is attached. Deleting the preset nulls this field (`ON DELETE SET NULL`) without
 *            deleting, disabling or otherwise editing the role. Defaults to null so payloads produced
 *            before this property existed decode as preset-less.
 * @property tools Set of tool-definition identifiers attached to the role. Referential integrity is
 *            enforced at the database level (the server stores the ids in the `agent_role_tools` join
 *            table); the wire shape is a plain set of ids, so duplicates are impossible.
 * @property spawnableAgentRoleIds Unordered identifiers of roles this role may spawn. The server validates
 *            that targets belong to the same user **and to the same project scope** as the role
 *            (same project id, or both unassociated — see [projectId]); self-spawn (the role granting
 *            itself) is allowed.
 * @property instructions Flat, type-tagged instruction list (see [AgentInstructionDto]) that is
 *            composed into the role's system prompt at turn time.
 * @property disabled Whether the role is disabled **for the current user**. The flag is derived from
 *            a per-user side table (`agent_role_disabled`): an absent row means enabled, a present
 *            row means disabled. Clients use it to hide roles from session selection (chat top bar)
 *            and to render the settings enable/disable switch; it defaults to false so payloads
 *            produced before this property existed (and fresh roles) decode as enabled.
 * @property projectId Identifier of the single user-owned project the role belongs to, or `null` for
 *            an **unassociated** role. A role belongs to at most one project — the relation was
 *            deliberately reduced from a set to a single id so `spawnableAgentRoleIds` can be
 *            enforced as same-project. Unassociated roles are offered by the session role selector
 *            only while the session has no project selected. Project membership also scopes the
 *            role-name uniqueness rule: same-named roles of the same user conflict only when they
 *            share the same scope (the same project id, or both unassociated). The default keeps
 *            payloads produced before this property existed decoding as unassociated.
 */
@Serializable
data class AgentRoleDto(
    val id: Long,
    val name: String,
    val displayName: String? = null,
    val description: String = "",
    val modelId: Long?,
    val modelSettingsId: Long?,
    val modelPresetId: Long? = null,
    val tools: Set<Long> = emptySet(),
    val spawnableAgentRoleIds: Set<Long> = emptySet(),
    val instructions: List<AgentInstructionDto> = emptyList(),
    val disabled: Boolean = false,
    val projectId: Long? = null
)
