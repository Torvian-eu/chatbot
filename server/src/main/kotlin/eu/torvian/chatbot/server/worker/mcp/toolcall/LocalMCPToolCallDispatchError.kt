package eu.torvian.chatbot.server.worker.mcp.toolcall

import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.WorkerMcpToolCallProtocolMappingError
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError

/**
 * Logical failure while dispatching or decoding a Local MCP tool call through the worker runtime.
 */
sealed interface LocalMCPToolCallDispatchError {
    /**
     * Indicates that the request payload could not be encoded for the worker protocol.
     *
     * @property error Shared worker-protocol mapping error.
     */
    data class RequestMappingFailed(
        val error: WorkerMcpToolCallProtocolMappingError
    ) : LocalMCPToolCallDispatchError

    /**
     * Indicates that the worker command dispatch failed before a usable result was returned.
     *
     * @property error Worker-dispatch lifecycle failure.
     */
    data class DispatchFailed(
        val error: WorkerCommandDispatchError
    ) : LocalMCPToolCallDispatchError

    /**
     * Indicates that the worker returned a result payload that could not be decoded into a tool result.
     *
     * @property error Shared worker-protocol mapping error.
     */
    data class ResultMappingFailed(
        val error: WorkerMcpToolCallProtocolMappingError
    ) : LocalMCPToolCallDispatchError
}
