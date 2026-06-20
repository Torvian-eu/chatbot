package eu.torvian.chatbot.server.worker.mcp.toolcall

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPToolExecutionRequest

/**
 * Dispatches Local MCP tool execution authorization to the assigned worker and decodes the worker result.
 */
interface LocalMCPToolCallDispatchService {
    /**
     * Dispatches a Local MCP tool execution request (signed authorization) to a connected worker.
     *
     * @param workerId Assigned worker identifier.
     * @param request Signed Local MCP execution authorization to forward to the worker runtime.
     * @return Either a dispatch error or the decoded tool-call result returned by the worker.
     */
    suspend fun dispatchToolCall(
        workerId: Long,
        request: SignedLocalMCPToolExecutionRequest
    ): Either<LocalMCPToolCallDispatchError, LocalMCPToolCallResult>
}
