package eu.torvian.chatbot.worker.protocol.handshake

/**
 * Snapshot of one worker hello/welcome handshake lifecycle.
 */
sealed interface SessionHandshakeState {
    /**
     * Marker state used after hello is emitted and before welcome validation completes.
     */
    data object Pending : SessionHandshakeState

    /**
     * Successful handshake state carrying negotiated session data.
     *
     * @property welcome Server-confirmed welcome data validated by the worker.
     */
    data class Succeeded(
        val welcome: SessionWelcomeState
    ) : SessionHandshakeState

    /**
     * Failed handshake state with a logical, user-actionable reason.
     *
     * @property reason Human-readable failure reason suitable for diagnostics.
     */
    data class Failed(
        val reason: String
    ) : SessionHandshakeState
}

