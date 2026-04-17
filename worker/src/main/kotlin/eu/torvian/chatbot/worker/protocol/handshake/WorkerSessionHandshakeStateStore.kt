package eu.torvian.chatbot.worker.protocol.handshake

/**
 * Stores worker hello/welcome handshake state in memory for runtime observability.
 */
interface WorkerSessionHandshakeStateStore {
    /**
     * Marks an interaction as waiting for `session.welcome`.
     *
     * @param interactionId Handshake interaction identifier.
     */
    fun markPending(interactionId: String)

    /**
     * Marks an interaction as successfully negotiated.
     *
     * @param interactionId Handshake interaction identifier.
     * @param welcome Validated welcome state to store.
     */
    fun markSucceeded(interactionId: String, welcome: WorkerSessionWelcomeState)

    /**
     * Marks an interaction as failed with a logical reason.
     *
     * @param interactionId Handshake interaction identifier.
     * @param reason Human-readable failure reason.
     */
    fun markFailed(interactionId: String, reason: String)

    /**
     * Looks up the latest handshake state for an interaction.
     *
     * @param interactionId Handshake interaction identifier.
     * @return Stored state when present; otherwise `null`.
     */
    fun get(interactionId: String): WorkerSessionHandshakeState?
}

