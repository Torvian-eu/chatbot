package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.tool.ToolCallApprovalResponse
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
     * An event sent by the client to provide the result of a local tool execution
     * that was requested by the server.
     *
     * @property result The result of the tool execution.
     */
    @Serializable
    data class LocalMCPToolResult(
        val result: LocalMCPToolCallResult
    ) : ChatClientEvent

    /**
     * An event sent by the client to provide the user's approval decision
     * for a tool call that was requested by the server.
     *
     * @property response The user's approval decision (approved/denied with optional reason).
     */
    @Serializable
    data class ToolCallApproval(
        val response: ToolCallApprovalResponse
    ) : ChatClientEvent
}