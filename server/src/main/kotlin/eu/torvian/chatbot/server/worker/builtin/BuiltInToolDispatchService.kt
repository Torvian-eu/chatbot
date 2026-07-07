package eu.torvian.chatbot.server.worker.builtin

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.SignedBuiltInToolExecutionRequest

/**
 * Dispatches a signed built-in tool execution request to the connected worker and decodes the
 * worker result back into a [BuiltInToolExecutionResult].
 */
interface BuiltInToolDispatchService {
    /**
     * @param workerId Assigned worker identifier.
     * @param request Signed built-in tool execution authorization to forward to the worker.
     * @return Either a dispatch error or the decoded tool-call result returned by the worker.
     */
    suspend fun dispatchToolCall(
        workerId: Long,
        request: SignedBuiltInToolExecutionRequest
    ): Either<BuiltInToolDispatchError, BuiltInToolExecutionResult>
}

