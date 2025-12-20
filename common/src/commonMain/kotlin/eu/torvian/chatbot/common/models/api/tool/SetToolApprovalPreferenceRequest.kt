package eu.torvian.chatbot.common.models.api.tool

import kotlinx.serialization.Serializable

/**
 * Request DTO for setting a user's tool approval preference.
 *
 * @property toolDefinitionId The ID of the tool definition to configure
 * @property autoApprove Whether to auto-approve (true) or auto-deny (false) this tool
 * @property conditions Optional JSON string for conditional auto-approval logic (reserved for future use)
 * @property denialReason Optional reason text for auto-denials, to be read by the LLM (reserved for future use)
 */
@Serializable
data class SetToolApprovalPreferenceRequest(
    val toolDefinitionId: Long,
    val autoApprove: Boolean,
    val conditions: String? = null,
    val denialReason: String? = null
)

