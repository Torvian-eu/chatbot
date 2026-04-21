package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

/**
 * Logical failure returned while mapping MCP runtime command DTOs to or from worker protocol payloads.
 *
 * @property InvalidCommandType Raised when a payload targets a command type other than the expected type.
 * @property SerializationFailed Raised when payload JSON encoding or decoding fails.
 */
sealed interface WorkerMcpRuntimeCommandProtocolMappingError {

    /**
     * Indicates that a payload carried an unexpected command type.
     *
     * @property expected Expected command type.
     * @property actual Actual command type that was received.
     */
    data class InvalidCommandType(
        val expected: String,
        val actual: String
    ) : WorkerMcpRuntimeCommandProtocolMappingError

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
    ) : WorkerMcpRuntimeCommandProtocolMappingError
}

