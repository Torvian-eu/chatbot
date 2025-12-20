package eu.torvian.chatbot.server.service.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult

sealed interface LocalMCPExecutorEvent {

    /**
     * Emitted when a tool execution is requested.
     *
     * @property request Details of the tool call request
     */
    data class ToolExecutionRequest(
        val request: LocalMCPToolCallRequest
    ) : LocalMCPExecutorEvent

    /**
     * Emitted when a tool execution completes.
     *
     * @property result Result of the tool execution
     */
    data class ToolExecutionResult(
        val result: LocalMCPToolCallResult
    ) : LocalMCPExecutorEvent

    /**
     * Emitted when a tool execution fails.
     *
     * @property toolCallId Unique identifier for the tool call
     * @property error Error that occurred during tool execution
     */
    data class ToolExecutionError(
        val toolCallId: Long,
        val error: LocalMCPExecutorError
    ) : LocalMCPExecutorEvent
}