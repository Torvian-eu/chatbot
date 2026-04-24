package eu.torvian.chatbot.worker.protocol.handshake

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe in-memory [SessionHandshakeContext] implementation.
 */
class InMemorySessionHandshakeContext : SessionHandshakeContext {
    /**
     * Current session handshake state.
     */
    private val handshakeState: AtomicReference<SessionHandshakeState?> = AtomicReference(null)

    /**
     * Deferred terminal state for the current session.
     */
    private val terminalState: AtomicReference<CompletableDeferred<SessionHandshakeState>> =
        AtomicReference(CompletableDeferred())

    override fun reset() {
        handshakeState.set(null)
        terminalState.set(CompletableDeferred())
    }

    override fun markPending() {
        handshakeState.set(SessionHandshakeState.Pending)
    }

    override fun markSucceeded(welcome: SessionWelcomeState) {
        val state = SessionHandshakeState.Succeeded(welcome)
        handshakeState.set(state)
        terminalState.get().complete(state)
    }

    override fun markFailed(reason: String) {
        val state = SessionHandshakeState.Failed(reason)
        handshakeState.set(state)
        terminalState.get().complete(state)
    }

    override fun get(): SessionHandshakeState? {
        return handshakeState.get()
    }

    override suspend fun awaitTerminalState(): SessionHandshakeState {
        return terminalState.get().await()
    }
}
