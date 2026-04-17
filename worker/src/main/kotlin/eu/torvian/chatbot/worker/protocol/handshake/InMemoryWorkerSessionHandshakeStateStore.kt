package eu.torvian.chatbot.worker.protocol.handshake

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [WorkerSessionHandshakeStateStore] implementation.
 */
class InMemoryWorkerSessionHandshakeStateStore : WorkerSessionHandshakeStateStore {
    /**
     * Handshake states keyed by interaction identifier.
     */
    private val handshakeStatesByInteractionId: ConcurrentHashMap<String, WorkerSessionHandshakeState> =
        ConcurrentHashMap()

    /**
     * @param interactionId Handshake interaction identifier.
     */
    override fun markPending(interactionId: String) {
        handshakeStatesByInteractionId[interactionId] = WorkerSessionHandshakeState.Pending
    }

    /**
     * @param interactionId Handshake interaction identifier.
     * @param welcome Validated welcome state to store.
     */
    override fun markSucceeded(interactionId: String, welcome: WorkerSessionWelcomeState) {
        handshakeStatesByInteractionId[interactionId] = WorkerSessionHandshakeState.Succeeded(welcome)
    }

    /**
     * @param interactionId Handshake interaction identifier.
     * @param reason Human-readable failure reason.
     */
    override fun markFailed(interactionId: String, reason: String) {
        handshakeStatesByInteractionId[interactionId] = WorkerSessionHandshakeState.Failed(reason)
    }

    /**
     * @param interactionId Handshake interaction identifier.
     * @return Stored state when present; otherwise `null`.
     */
    override fun get(interactionId: String): WorkerSessionHandshakeState? {
        return handshakeStatesByInteractionId[interactionId]
    }
}

