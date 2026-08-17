package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionAuthorization
import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

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

    /**
     * An event sent by the operator (in v1 the client app) to approve or reject one operator tool call.
     *
     * Operator tools (e.g. `spawn_agent`) are executed by the operator over the chat WebSocket, so no
     * signed request is needed — there is no worker dispatch for the operator tool call itself. The
     * event is normalized by the server into `ToolCallApprovalSubmission.OperatorToolApproval` and
     * consumed by the approval gate of the matching tool call.
     *
     * @property toolCallId Persisted tool-call identifier this decision refers to.
     * @property approved Whether execution was approved.
     * @property denialReason Optional denial reason supplied by the user or an auto-deny preference.
     */
    @Serializable
    @SerialName("operator_tool_call_approval")
    data class OperatorToolCallApproval(
        val toolCallId: Long,
        val approved: Boolean,
        val denialReason: String? = null
    ) : ChatClientEvent

    /**
     * An event sent by the operator to return the result of one operator tool execution.
     *
     * This is deliberately **not** an approval: it flows from the operator to the server on a dedicated
     * result channel and is consumed only by the executor of the matching tool call, correlated by
     * [toolCallId].
     *
     * @property toolCallId Persisted tool-call identifier this result refers to (echoed from
     *            `OperatorToolExecutionRequested`).
     * @property output The tool's textual output (e.g. the spawned agent's summary), or `null` on error.
     * @property isError Whether the execution failed.
     * @property errorMessage Optional error detail surfaced to the LLM when [isError] is true.
     */
    @Serializable
    @SerialName("tool_execution_result")
    data class ToolExecutionResult(
        val toolCallId: Long,
        val output: String? = null,
        val isError: Boolean = false,
        val errorMessage: String? = null
    ) : ChatClientEvent
}