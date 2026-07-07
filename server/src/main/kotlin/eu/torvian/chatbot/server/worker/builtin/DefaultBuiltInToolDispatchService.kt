package eu.torvian.chatbot.server.worker.builtin

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toBuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.SignedBuiltInToolExecutionRequest
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchService

/**
 * Default implementation of [BuiltInToolDispatchService].
 *
 * Converts the signed authorization to a worker command payload, dispatches it, and decodes the
 * worker result.
 *
 * @property workerCommandDispatchService Generic worker command dispatcher.
 */
class DefaultBuiltInToolDispatchService(
    private val workerCommandDispatchService: WorkerCommandDispatchService
) : BuiltInToolDispatchService {

    override suspend fun dispatchToolCall(
        workerId: Long,
        request: SignedBuiltInToolExecutionRequest
    ): Either<BuiltInToolDispatchError, BuiltInToolExecutionResult> = either {
        val requestPayload = request.toWorkerCommandRequestPayload()
            .mapLeft { mappingError -> BuiltInToolDispatchError.RequestMappingFailed(mappingError) }
            .bind()

        val dispatchResult = workerCommandDispatchService.dispatch(workerId, requestPayload)
            .mapLeft { dispatchError -> BuiltInToolDispatchError.DispatchFailed(dispatchError) }
            .bind()

        dispatchResult.result.toBuiltInToolExecutionResult()
            .mapLeft { mappingError -> BuiltInToolDispatchError.ResultMappingFailed(mappingError) }
            .bind()
    }
}

