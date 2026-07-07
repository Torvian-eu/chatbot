package eu.torvian.chatbot.server.worker.builtin

import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.BuiltInToolProtocolMappingError
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError

/**
 * Logical failure while dispatching or decoding a built-in tool call through the worker runtime.
 */
sealed interface BuiltInToolDispatchError {
    /**
     * Indicates that the request payload could not be encoded for the worker protocol.
     */
    data class RequestMappingFailed(
        val error: BuiltInToolProtocolMappingError
    ) : BuiltInToolDispatchError

    /**
     * Indicates that the worker command dispatch failed before a usable result was returned.
     */
    data class DispatchFailed(
        val error: WorkerCommandDispatchError
    ) : BuiltInToolDispatchError

    /**
     * Indicates that the worker returned a result payload that could not be decoded.
     */
    data class ResultMappingFailed(
        val error: BuiltInToolProtocolMappingError
    ) : BuiltInToolDispatchError
}

