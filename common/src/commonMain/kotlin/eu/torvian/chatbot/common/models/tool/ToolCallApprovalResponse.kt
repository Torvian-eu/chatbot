package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.Serializable

/**
 * Response from client to server with user's decision on tool call approval.
 *
 * @property toolCallId Unique identifier for the tool call being approved/denied
 * @property approved True if user approved execution, false if denied
 * @property denialReason Optional reason provided by user when denying execution (only if approved=false)
 */
@Serializable
data class ToolCallApprovalResponse(
    val toolCallId: Long,
    val approved: Boolean,
    val denialReason: String? = null
)

