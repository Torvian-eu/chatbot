package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.tool.ToolCallApprovalResponse
import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionAuthorization

/**
 * Defines events sent from the client to the server over the WebSocket connection
 * for the `/api/v1/sessions/{sessionId}/messages` endpoint.
 */
@Serializable
sealed interface ChatClientEvent {
    /**
     * The initial event sent by the client to start a new message processing flow.
     *
     * @property request The details of the new message to be processed.
     */
    @Serializable
    data class ProcessNewMessage(val request: ProcessNewMessageRequest) : ChatClientEvent

    /**
     * Requests cancellation of the active message turn without closing the WebSocket.
     *
     * The server keeps the connection alive long enough to persist and publish terminal
     * cancellation events before completing the socket normally.
     */
    @Serializable
    @SerialName("cancel")
    data object Cancel : ChatClientEvent

    /**
     * Requests the active turn to finish its current assistant/tool step without starting another
     * assistant iteration.
     */
    @Serializable
    @SerialName("pause")
    data object Pause : ChatClientEvent

    /**
     * An event sent by the client to provide an approval decision for a non-Local-MCP tool call.
     *
     * Local MCP approvals must use [LocalMcpToolCallApproval] so the server can forward detached app
     * authorization metadata to the worker.
     *
     * @property response The user's approval decision (approved/denied with optional reason).
     */
    @Serializable
    data class ToolCallApproval(
        val response: ToolCallApprovalResponse
    ) : ChatClientEvent

    /**
     * An event sent by the client to authorize one Local MCP tool call with an app-generated detached signature.
     *
     * The signed [SignedRequest.payload] contains the exact serialized JSON of [LocalMCPToolExecutionAuthorization],
     * which the server relays to the worker. The worker verifies the signature and decodes the authorization
     * from [signedRequest].payload as the sole source of truth for execution parameters.
     *
     * @property signedRequest Detached signature metadata and the exact Local MCP execution authorization payload
     *   (serialized as [LocalMCPToolExecutionAuthorization] JSON) signed by the app.
     */
    @Serializable
    data class LocalMcpToolCallApproval(
        val signedRequest: SignedRequest
    ) : ChatClientEvent

    /**
     * An event sent by the client to authorize one built-in worker tool call with an app-generated detached signature.
     *
     * The signed [SignedRequest.payload] contains the exact serialized JSON of [BuiltInToolExecutionAuthorization],
     * which the server relays to the worker. The worker verifies the signature and decodes the authorization
     * from [signedRequest].payload as the sole source of truth for execution parameters.
     *
     * @property signedRequest Detached signature metadata and the exact built-in tool execution authorization payload
     *   (serialized as [BuiltInToolExecutionAuthorization] JSON) signed by the app.
     */
    @Serializable
    @SerialName("builtin_tool_call_approval")
    data class BuiltInToolCallApproval(
        val signedRequest: SignedRequest
    ) : ChatClientEvent
}