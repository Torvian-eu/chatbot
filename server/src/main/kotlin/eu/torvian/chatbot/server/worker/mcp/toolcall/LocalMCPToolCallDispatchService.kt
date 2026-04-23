package eu.torvian.chatbot.server.worker.mcp.toolcall

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult

/**
 * Dispatches Local MCP tool calls to the assigned worker and decodes the worker result.
 */
interface LocalMCPToolCallDispatchService {
    /**
     * Dispatches a Local MCP tool call to a connected worker.
     *
     * @param workerId Assigned worker identifier.
     * @param request Local MCP tool-call request to forward to the worker runtime.
     * @return Either a dispatch error or the decoded tool-call result returned by the worker.
     */
    suspend fun dispatchToolCall(
        workerId: Long,
        request: LocalMCPToolCallRequest
    ): Either<LocalMCPToolCallDispatchError, LocalMCPToolCallResult>
}
