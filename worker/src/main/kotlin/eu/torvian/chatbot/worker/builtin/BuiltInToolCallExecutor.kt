package eu.torvian.chatbot.worker.builtin

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult

/**
 * Dispatches built-in tool execution requests to the registered implementations.
 */
interface BuiltInToolCallExecutor {
    /**
     * Executes the request by resolving the public tool name to an implementation and running it.
     *
     * @param request Decoded built-in tool execution request.
     * @return Built-in tool execution result; returns an [BuiltInToolExecutionError.UNKNOWN_TOOL]
     *   result if no implementation matches the tool name.
     */
    suspend fun execute(request: BuiltInToolExecutionRequest): BuiltInToolExecutionResult
}

