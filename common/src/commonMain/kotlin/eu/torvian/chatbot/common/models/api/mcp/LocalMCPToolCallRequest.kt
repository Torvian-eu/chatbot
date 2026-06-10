package eu.torvian.chatbot.common.models.api.mcp

import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.Serializable

/**
 * Worker-facing Local MCP tool-call request plus the detached app authorization snapshot that must match it.
 *
 * The worker treats the execution fields in this DTO as the authoritative command it was asked to run,
 * then verifies that they still match the signed app authorization before invoking the MCP runtime.
 *
 * @property toolCallId Persisted tool-call identifier.
 * @property sessionId Session that owns the tool call.
 * @property messageId Assistant message that owns the tool call.
 * @property toolDefinitionId Persisted Local MCP tool definition expected to run.
 * @property toolName User-visible tool name that appeared in the chat tool call.
 * @property serverId Local MCP server that should execute the tool.
 * @property mcpToolName Runtime MCP tool name that should be invoked on the worker.
 * @property inputJson Exact JSON argument string to pass to the MCP runtime.
 * @property approved Approval state relayed by the server for worker-side comparison with the signed payload.
 * @property denialReason Optional denial reason relayed by the server for worker-side comparison.
 * @property signedAuthorization Detached app authorization that must validate and match the request.
 */
@Serializable
data class LocalMCPToolCallRequest(
    val toolCallId: Long,
    val sessionId: Long,
    val messageId: Long,
    val toolDefinitionId: Long,
    val toolName: String,
    val serverId: Long,
    val mcpToolName: String,
    val inputJson: String?,
    val approved: Boolean,
    val denialReason: String? = null,
    val signedAuthorization: SignedRequest? = null
)