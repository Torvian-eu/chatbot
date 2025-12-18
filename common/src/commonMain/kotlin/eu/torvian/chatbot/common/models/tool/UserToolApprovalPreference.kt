package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.Serializable

/**
 * Represents a user's preference for automatic approval or denial of tool calls.
 *
 * Users can configure which tools should be automatically approved or denied
 * without manual intervention, streamlining workflows for trusted or unwanted tools.
 *
 * @property userId The ID of the user who owns this preference
 * @property toolDefinitionId The ID of the tool definition this preference applies to
 * @property autoApprove Whether to auto-approve (true) or auto-deny (false) tool calls for this tool
 * @property conditions Optional JSON string for conditional auto-approval logic (reserved for future implementation)
 * @property denialReason Optional reason text provided when auto-denying a tool call (used by LLM, reserved for future implementation)
 */
@Serializable
data class UserToolApprovalPreference(
    val userId: Long,
    val toolDefinitionId: Long,
    val autoApprove: Boolean,
    val conditions: String? = null,
    val denialReason: String? = null
)

