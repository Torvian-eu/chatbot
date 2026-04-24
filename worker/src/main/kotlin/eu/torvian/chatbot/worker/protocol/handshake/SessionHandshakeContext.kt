package eu.torvian.chatbot.worker.protocol.handshake

/**
 * Stores the current worker hello/welcome handshake state for the active websocket session.
 */
interface SessionHandshakeContext {
    /**
     * Clears any previously recorded handshake state for the current session.
     */
    fun reset()

    /**
     * Marks the current session handshake as waiting for `session.welcome`.
     */
    fun markPending()

    /**
     * Marks the current session handshake as successfully negotiated.
     *
     * @param welcome Validated welcome state to store.
     */
    fun markSucceeded(welcome: SessionWelcomeState)

    /**
     * Marks the current session handshake as failed with a logical reason.
     *
     * @param reason Human-readable failure reason.
     */
    fun markFailed(reason: String)

    /**
     * Returns the current session handshake state, if any.
     *
     * @return Stored state when present; otherwise `null`.
     */
    fun get(): SessionHandshakeState?

    /**
     * Returns the terminal handshake state for the current session.
     *
     * @return The first successful or failed handshake state that was recorded.
     */
    suspend fun awaitTerminalState(): SessionHandshakeState

    /**
     * Returns `true` if the current session handshake state is a successful negotiation.
     *
     * @return `true` when the current state is [SessionHandshakeState.Succeeded]; otherwise `false`.
     */
    fun hasSucceeded(): Boolean {
        return get() is SessionHandshakeState.Succeeded
    }
}
