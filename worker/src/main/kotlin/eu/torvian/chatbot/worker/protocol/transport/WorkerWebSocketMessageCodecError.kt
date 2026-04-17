package eu.torvian.chatbot.worker.protocol.transport

/**
 * Logical failures raised while decoding inbound WebSocket text frames.
 */
sealed interface WorkerWebSocketMessageCodecError {
    /**
     * Indicates the inbound text payload is not a valid worker protocol envelope.
     *
     * @property reason Human-readable decode failure reason.
     */
    data class DecodeFailed(val reason: String) : WorkerWebSocketMessageCodecError
}