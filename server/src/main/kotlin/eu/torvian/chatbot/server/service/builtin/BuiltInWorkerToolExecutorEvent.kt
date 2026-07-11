package eu.torvian.chatbot.server.service.builtin

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult

/**
 * Outcomes emitted by the Built-in Worker executor.
 */
sealed interface BuiltInWorkerToolExecutorEvent {
    /**
     * Emitted when a tool execution completes.
     */
    data class ToolExecutionResult(
        val result: BuiltInToolExecutionResult
    ) : BuiltInWorkerToolExecutorEvent

    /**
     * Emitted when a tool execution fails.
     */
    data class ToolExecutionError(
        val toolCallId: Long,
        val error: BuiltInWorkerToolExecutorError
    ) : BuiltInWorkerToolExecutorEvent
}

