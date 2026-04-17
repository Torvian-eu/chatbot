package eu.torvian.chatbot.worker.protocol.ids

import java.util.UUID

/**
 * UUID-based [WorkerInteractionIdProvider] implementation.
 */
class UuidWorkerInteractionIdProvider : WorkerInteractionIdProvider {
    /**
     * Produces a random UUID string.
     *
     * @return UUID string suitable for protocol interaction identifiers.
     */
    override fun nextInteractionId(): String = UUID.randomUUID().toString()
}