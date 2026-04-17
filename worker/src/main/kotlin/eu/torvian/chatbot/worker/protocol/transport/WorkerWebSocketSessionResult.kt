package eu.torvian.chatbot.worker.protocol.transport

/**
 * Outcome returned after one session lifecycle finishes.
 *
 * @property stableConnection Indicates whether the socket was established and progressed beyond handshake setup.
 * @property authRejected Indicates whether the server rejected the connection due to authentication.
 */
data class WorkerWebSocketSessionResult(
    val stableConnection: Boolean,
    val authRejected: Boolean
)