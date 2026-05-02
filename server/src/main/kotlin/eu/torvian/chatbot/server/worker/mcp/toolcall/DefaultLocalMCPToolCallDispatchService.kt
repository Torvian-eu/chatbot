package eu.torvian.chatbot.server.worker.mcp.toolcall

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toLocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandRequestPayload
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchService

/**
 * Default implementation of the worker-backed Local MCP tool-call dispatch adapter.
 *
 * @property workerCommandDispatchService Generic worker command dispatcher.
 */
class DefaultLocalMCPToolCallDispatchService(
    private val workerCommandDispatchService: WorkerCommandDispatchService
) : LocalMCPToolCallDispatchService {
    override suspend fun dispatchToolCall(
        workerId: Long,
        request: LocalMCPToolCallRequest
    ): Either<LocalMCPToolCallDispatchError, LocalMCPToolCallResult> = either {
        val requestPayload = request.toWorkerCommandRequestPayload()
            .mapLeft { mappingError -> LocalMCPToolCallDispatchError.RequestMappingFailed(mappingError) }
            .bind()

        val dispatchResult = workerCommandDispatchService.dispatch(workerId, requestPayload)
            .mapLeft { dispatchError -> LocalMCPToolCallDispatchError.DispatchFailed(dispatchError) }
            .bind()

        dispatchResult.result.toLocalMCPToolCallResult()
            .mapLeft { mappingError -> LocalMCPToolCallDispatchError.ResultMappingFailed(mappingError) }
            .bind()
    }
}
