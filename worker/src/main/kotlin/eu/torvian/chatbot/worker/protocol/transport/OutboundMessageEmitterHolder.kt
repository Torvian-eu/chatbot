package eu.torvian.chatbot.worker.protocol.transport

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import java.util.concurrent.atomic.AtomicReference
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Mutable bridge that keeps [OutboundMessageEmitter] stable for runtime components while
 * allowing transport code to bind and unbind the active socket sender.
 */
class OutboundMessageEmitterHolder : OutboundMessageEmitter {
    /**
     * Atomic reference to the currently bound transport emitter, if any.
     */
    private val activeEmitterRef: AtomicReference<OutboundMessageEmitter?> = AtomicReference(null)

    /**
     * Publishes the emitter that should receive outbound protocol envelopes.
     *
     * @param emitter Active transport emitter tied to the currently connected WebSocket session.
     */
    fun bind(emitter: OutboundMessageEmitter) {
        activeEmitterRef.set(emitter)
    }

    /**
     * Clears the active transport emitter to prevent sending over a stale session.
     */
    fun unbind() {
        activeEmitterRef.set(null)
    }

    /**
     * Emits one outbound protocol envelope through the currently bound transport emitter.
     *
     * @param message Outbound worker protocol envelope.
     */
    override suspend fun emit(message: WorkerProtocolMessage) {
        val activeEmitter = activeEmitterRef.get()
        if (activeEmitter == null) {
            logger.debug(
                "No outbound transport bound; dropping protocol message (id={}, type={})",
                message.id,
                message.type
            )
            return
        }

        activeEmitter.emit(message)
    }

    companion object {
        /**
         * Logger used for diagnostics when messages are emitted while disconnected.
         */
        private val logger: Logger = LogManager.getLogger(OutboundMessageEmitterHolder::class.java)
    }
}

