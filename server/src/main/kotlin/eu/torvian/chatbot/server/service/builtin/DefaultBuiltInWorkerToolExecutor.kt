package eu.torvian.chatbot.server.service.builtin

import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.BuiltInToolProtocolMappingError
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.SignedBuiltInToolExecutionRequest
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.worker.builtin.BuiltInToolDispatchError
import eu.torvian.chatbot.server.worker.builtin.BuiltInToolDispatchService
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError

/**
 * Default implementation of [BuiltInWorkerToolExecutor].
 *
 * Wraps the [SignedRequest] in a [SignedBuiltInToolExecutionRequest] and forwards it through the
 * [BuiltInToolDispatchService] to the worker identified by [BuiltInWorkerToolDefinition.workerId].
 *
 * Dispatch failures are translated into the typed [BuiltInWorkerToolExecutorError] hierarchy so the
 * chat loop can distinguish a [BuiltInWorkerToolExecutorError.Timeout] from a generic
 * [BuiltInWorkerToolExecutorError.OtherError]. This mirrors the structured translation performed by
 * `LocalMCPExecutor`; unlike Local MCP, no server-side lookup is required because the worker is
 * identified directly on the tool definition.
 */
class DefaultBuiltInWorkerToolExecutor(
    private val dispatchService: BuiltInToolDispatchService
) : BuiltInWorkerToolExecutor {

    override suspend fun executeTool(
        toolDefinition: BuiltInWorkerToolDefinition,
        toolCall: ToolCall,
        signedRequest: SignedRequest
    ): BuiltInWorkerToolExecutorEvent {
        // Validate the definition locally so a misconfigured row never reaches the worker. The
        // worker id is the only affinity required to dispatch the signed request.
        if (toolDefinition.workerId <= 0L) {
            return BuiltInWorkerToolExecutorEvent.ToolExecutionError(
                toolCallId = toolCall.id,
                error = BuiltInWorkerToolExecutorError.OtherError(
                    "Built-in tool definition ${toolDefinition.id} is not bound to a worker"
                ),
            )
        }

        val request = SignedBuiltInToolExecutionRequest(signedRequest = signedRequest)
        return dispatchService.dispatchToolCall(toolDefinition.workerId, request)
            .fold(
                ifLeft = { error -> mapError(error, toolCall.id) },
                ifRight = { result ->
                    BuiltInWorkerToolExecutorEvent.ToolExecutionResult(result = result)
                }
            )
    }

    /**
     * Translates a [BuiltInToolDispatchError] into the executor-level error hierarchy.
     *
     * @param error Dispatch failure returned by the worker adapter.
     * @param toolCallId Persisted tool-call identifier used for diagnostics.
     * @return A typed executor error suitable for chat-loop persistence.
     */
    private fun mapError(error: BuiltInToolDispatchError, toolCallId: Long): BuiltInWorkerToolExecutorEvent {
        return when (error) {
            is BuiltInToolDispatchError.RequestMappingFailed -> {
                BuiltInWorkerToolExecutorEvent.ToolExecutionError(
                    toolCallId = toolCallId,
                    error = BuiltInWorkerToolExecutorError.InvalidInput(
                        "Failed to encode built-in tool call request: ${error.error.describe()}"
                    ),
                )
            }

            is BuiltInToolDispatchError.ResultMappingFailed -> {
                BuiltInWorkerToolExecutorEvent.ToolExecutionError(
                    toolCallId = toolCallId,
                    error = BuiltInWorkerToolExecutorError.OtherError(
                        "Failed to decode built-in tool call result: ${error.error.describe()}"
                    ),
                )
            }

            is BuiltInToolDispatchError.DispatchFailed -> {
                BuiltInWorkerToolExecutorEvent.ToolExecutionError(
                    toolCallId = toolCallId,
                    error = error.error.toExecutorError(),
                )
            }
        }
    }

    /**
     * Converts a [WorkerCommandDispatchError] into the executor-level error type, preserving the
     * `Timeout` semantic so the chat loop can apply a different status if needed.
     */
    private fun WorkerCommandDispatchError.toExecutorError(): BuiltInWorkerToolExecutorError = when (this) {
        is WorkerCommandDispatchError.TimedOut -> {
            BuiltInWorkerToolExecutorError.Timeout(
                "Built-in tool execution timed out after ${timeout.inWholeSeconds} seconds"
            )
        }

        is WorkerCommandDispatchError.WorkerNotConnected -> {
            BuiltInWorkerToolExecutorError.OtherError(
                "Assigned worker $workerId is not connected"
            )
        }

        is WorkerCommandDispatchError.SessionDisconnected -> {
            BuiltInWorkerToolExecutorError.OtherError(
                "Assigned worker $workerId disconnected while executing tool call: ${reason ?: "unknown reason"}"
            )
        }

        is WorkerCommandDispatchError.SendFailed -> {
            BuiltInWorkerToolExecutorError.OtherError(
                "Failed to send built-in tool call to worker $workerId: $reason"
            )
        }

        is WorkerCommandDispatchError.MalformedLifecyclePayload -> {
            BuiltInWorkerToolExecutorError.OtherError(
                "Worker returned malformed tool-call lifecycle payload ($messageType): $reason"
            )
        }

        is WorkerCommandDispatchError.Rejected -> {
            BuiltInWorkerToolExecutorError.OtherError(
                "Worker rejected built-in tool call: ${rejection.message}"
            )
        }

        is WorkerCommandDispatchError.DuplicateInteractionId -> {
            BuiltInWorkerToolExecutorError.OtherError(
                "Worker command dispatch generated a duplicate interaction id: $interactionId"
            )
        }
    }

    /**
     * Formats built-in protocol mapping errors into a human-readable diagnostic string.
     */
    private fun BuiltInToolProtocolMappingError.describe(): String = when (this) {
        is BuiltInToolProtocolMappingError.InvalidCommandType -> {
            "expected $expected but received $actual"
        }

        is BuiltInToolProtocolMappingError.SerializationFailed -> {
            "$operation $targetType failed: ${details ?: "unknown serialization failure"}"
        }
    }
}
