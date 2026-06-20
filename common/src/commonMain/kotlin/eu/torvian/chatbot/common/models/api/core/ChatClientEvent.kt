package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.tool.ToolCallApprovalResponse
import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.Serializable

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
}