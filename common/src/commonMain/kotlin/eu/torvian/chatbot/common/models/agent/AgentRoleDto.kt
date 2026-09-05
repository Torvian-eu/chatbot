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
 * @property id Immutable, database-generated identifier.
 * @property name Unique (per user) machine-readable name of the role.
 * @property displayName Optional human-friendly display name; clients fall back to [name].
 * @property description Free-form description of the role's purpose.
 * @property modelId Identifier of the [LLMModel] the role uses. Null after the referenced model is
 *            deleted (`ON DELETE SET NULL`); the role is then non-sendable until repaired.
 * @property modelSettingsId Identifier of the [ModelSettings] profile (CHAT or RESPONSES) the role
 *            uses. Null after the referenced settings are deleted; the role is then non-sendable.
 * @property tools Set of tool-definition identifiers attached to the role. Referential integrity is
 *            enforced at the database level (the server stores the ids in the `agent_role_tools` join
 *            table); the wire shape is a plain set of ids, so duplicates are impossible.
 * @property spawnableAgentRoleIds Unordered identifiers of roles this role may spawn. The server validates
 *            that targets belong to the same user; self-spawn (the role granting itself) is allowed.
 * @property instructions Flat, type-tagged instruction list (see [AgentInstructionDto]) that is
 *            composed into the role's system prompt at turn time.
 * @property disabled Whether the role is disabled **for the current user**. The flag is derived from
 *            a per-user side table (`agent_role_disabled`): an absent row means enabled, a present
 *            row means disabled. Clients use it to hide roles from session selection (chat top bar)
 *            and to render the settings enable/disable switch; it defaults to false so payloads
 *            produced before this property existed (and fresh roles) decode as enabled.
 */
@Serializable
data class AgentRoleDto(
    val id: Long,
    val name: String,
    val displayName: String? = null,
    val description: String = "",
    val modelId: Long?,
    val modelSettingsId: Long?,
    val tools: Set<Long> = emptySet(),
    val spawnableAgentRoleIds: Set<Long> = emptySet(),
    val instructions: List<AgentInstructionDto> = emptyList(),
    val disabled: Boolean = false
)
