package eu.torvian.chatbot.common.models.api.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import kotlinx.serialization.Serializable

/**
 * Request body for updating an existing user-defined agent role.
 *
 * Mirrors [CreateAgentRoleRequest]: all configuration fields are present and are replaced wholesale on
 * update (the role's configuration is rewritten, so the update is a full replacement, not a patch).
 *
 * @property name Unique (per user) machine-readable role name, non-blank and at most 255 characters.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the role.
 * @property modelId Identifier of the LLM model the role uses.
 * @property modelSettingsId Identifier of the settings profile (CHAT or RESPONSES) the role uses; it
 *            must belong to [modelId] and be chat-capable.
 * @property toolIds Set of tool-definition identifiers to attach to the role. Duplicates are
 *            impossible at the wire level (a set), so no service-side de-duplication is needed.
 * @property instructions Flat instruction list. A `model_settings` instruction is bound by the server
 *            to the role's own [modelSettingsId]; its `message` is ignored on input and re-resolved.
 */
@Serializable
data class UpdateAgentRoleRequest(
    val name: String,
    val displayName: String? = null,
    val description: String = "",
    val modelId: Long,
    val modelSettingsId: Long,
    val toolIds: Set<Long> = emptySet(),
    val instructions: List<AgentInstructionDto> = emptyList()
)
