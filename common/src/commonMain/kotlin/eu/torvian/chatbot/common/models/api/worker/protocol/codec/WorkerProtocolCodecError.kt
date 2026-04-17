package eu.torvian.chatbot.common.models.api.worker.protocol.codec

/**
 * Logical failure returned by the generic worker protocol payload codec.
 */
sealed interface WorkerProtocolCodecError {
    /**
     * Indicates that encoding or decoding the payload failed.
     *
     * @property operation Codec operation that failed, such as `encode` or `decode`.
     * @property targetType Human-readable type name that was being processed.
     * @property details Optional diagnostic details from the underlying serializer.
     */
    data class SerializationFailed(
        val operation: String,
        val targetType: String,
        val details: String? = null
    ) : WorkerProtocolCodecError
}


