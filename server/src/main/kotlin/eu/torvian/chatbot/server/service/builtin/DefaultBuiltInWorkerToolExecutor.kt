package eu.torvian.chatbot.server.service.builtin

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.SignedBuiltInToolExecutionRequest
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.worker.builtin.BuiltInToolDispatchError
import eu.torvian.chatbot.server.worker.builtin.BuiltInToolDispatchService

/**
 * Default implementation of [BuiltInWorkerToolExecutor].
 *
 * Wraps the [SignedRequest] in a [SignedBuiltInToolExecutionRequest] and forwards it through the
 * [BuiltInToolDispatchService] to the worker identified by [BuiltInWorkerToolDefinition.workerId].
 */
class DefaultBuiltInWorkerToolExecutor(
    private val dispatchService: BuiltInToolDispatchService
) : BuiltInWorkerToolExecutor {

    override suspend fun executeTool(
        toolDefinition: BuiltInWorkerToolDefinition,
        toolCall: ToolCall,
        signedRequest: SignedRequest
    ): BuiltInWorkerToolExecutorEvent {
        val request = SignedBuiltInToolExecutionRequest(signedRequest = signedRequest)
        return dispatchService.dispatchToolCall(toolDefinition.workerId, request)
            .fold(
                ifLeft = { error -> mapError(error, toolCall.id) },
                ifRight = { result ->
                    BuiltInWorkerToolExecutorEvent.ToolExecutionResult(result = result)
                }
            )
    }

    private fun mapError(error: BuiltInToolDispatchError, toolCallId: Long): BuiltInWorkerToolExecutorEvent {
        val message = when (error) {
            is BuiltInToolDispatchError.RequestMappingFailed -> "Failed to encode built-in tool call request: ${error.error}"
            is BuiltInToolDispatchError.ResultMappingFailed -> "Failed to decode built-in tool call result: ${error.error}"
            is BuiltInToolDispatchError.DispatchFailed -> "Built-in tool dispatch failed: ${error.error}"
        }
        return BuiltInWorkerToolExecutorEvent.ToolExecutionError(
            toolCallId = toolCallId,
            error = BuiltInWorkerToolExecutorError.OtherError(message),
        )
    }
}

