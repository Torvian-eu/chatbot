package eu.torvian.chatbot.worker.protocol.transport

/**
 * Outcome returned after one session lifecycle finishes.
 *
 * @property stableConnection Indicates whether the socket completed the handshake successfully and remained open normally.
 * @property authRejected Indicates whether the server rejected the connection due to authentication.
 */
data class WebSocketSessionResult(
    val stableConnection: Boolean,
    val authRejected: Boolean
)