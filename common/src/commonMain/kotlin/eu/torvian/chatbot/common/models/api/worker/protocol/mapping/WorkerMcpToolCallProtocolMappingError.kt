package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

/**
 * Logical failure returned while mapping MCP tool-call DTOs to or from worker protocol payloads.
 *
 * @property InvalidCommandType Raised when a request payload targets the wrong command type.
 * @property SerializationFailed Raised when payload JSON encoding or decoding fails.
 */
sealed interface WorkerMcpToolCallProtocolMappingError {

    /**
     * Indicates that a request payload carried an unexpected command type.
     *
     * @property expected Expected command type.
     * @property actual Actual command type that was received.
     */
    data class InvalidCommandType(
        val expected: String,
        val actual: String
    ) : WorkerMcpToolCallProtocolMappingError

    /**
     * Indicates that payload encoding or decoding failed.
     *
     * @property operation Codec operation that failed.
     * @property targetType Human-readable payload type involved in the failure.
     * @property details Optional diagnostic details.
     */
    data class SerializationFailed(
        val operation: String,
        val targetType: String,
        val details: String? = null
    ) : WorkerMcpToolCallProtocolMappingError
}


