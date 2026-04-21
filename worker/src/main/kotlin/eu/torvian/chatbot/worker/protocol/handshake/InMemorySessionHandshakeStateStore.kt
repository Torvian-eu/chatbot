package eu.torvian.chatbot.worker.protocol.handshake

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [SessionHandshakeStateStore] implementation.
 */
class InMemorySessionHandshakeStateStore : SessionHandshakeStateStore {
    /**
     * Handshake states keyed by interaction identifier.
     */
    private val handshakeStatesByInteractionId: ConcurrentHashMap<String, SessionHandshakeState> =
        ConcurrentHashMap()

    override fun markPending(interactionId: String) {
        handshakeStatesByInteractionId[interactionId] = SessionHandshakeState.Pending
    }

    override fun markSucceeded(interactionId: String, welcome: SessionWelcomeState) {
        handshakeStatesByInteractionId[interactionId] = SessionHandshakeState.Succeeded(welcome)
    }

    override fun markFailed(interactionId: String, reason: String) {
        handshakeStatesByInteractionId[interactionId] = SessionHandshakeState.Failed(reason)
    }

    override fun get(interactionId: String): SessionHandshakeState? {
        return handshakeStatesByInteractionId[interactionId]
    }
}

