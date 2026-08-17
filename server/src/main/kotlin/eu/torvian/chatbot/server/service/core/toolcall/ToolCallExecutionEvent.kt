package eu.torvian.chatbot.server.service.core.toolcall

import eu.torvian.chatbot.common.models.tool.ToolCall

/**
 * Lifecycle events emitted by the tool-call orchestrator during approval and execution.
 *
 * These events are internal to the server service layer and are translated into public
 * [eu.torvian.chatbot.server.service.core.MessageEvent] or [eu.torvian.chatbot.server.service.core.MessageStreamEvent]
 * by the chat service.
 */
sealed interface ToolCallExecutionEvent {
    /**
     * Emitted when a tool call begins execution after approval.
     *
     * @property toolCall The tool call that is now EXECUTING.
     */
    data class ToolCallExecuting(val toolCall: ToolCall) : ToolCallExecutionEvent

    /**
     * Emitted when a single tool execution completes, including denials and errors.
     *
     * @property toolCall The completed tool call with its final status.
     */
    data class ToolCallCompleted(val toolCall: ToolCall) : ToolCallExecutionEvent

    /**
     * Emitted when a tool call requires user approval before execution.
     *
     * @property toolCall The tool call awaiting approval (with AWAITING_APPROVAL status).
     */
    data class ToolCallApprovalRequested(val toolCall: ToolCall) : ToolCallExecutionEvent

    /**
     * Emitted to relay one operator tool execution to the operator.
     *
     * This is a generic envelope: [payloadJson] is the JSON-serialized, tool-specific execution
     * request (e.g. [eu.torvian.chatbot.common.models.agent.AgentSpawnRequest] for `spawn_agent`) and
     * [toolName] tells the operator which deserializer to use. The operator echoes [toolCallId] back
     * in `ChatClientEvent.ToolExecutionResult` to correlate the reply.
     *
     * @property toolCallId Persisted tool-call identifier (correlation key).
     * @property toolName Operator-tool name (e.g. `spawn_agent`); unique per user because operator
     *            tools are per-user instances, so it doubles as the payload discriminator.
     * @property payloadJson JSON text of the tool-specific payload.
     */
    data class OperatorToolExecutionRequested(
        val toolCallId: Long,
        val toolName: String,
        val payloadJson: String
    ) : ToolCallExecutionEvent
}
