package eu.torvian.chatbot.worker.protocol.ids

import java.util.UUID

/**
 * UUID-based [WorkerMessageIdProvider] implementation.
 */
class UuidWorkerMessageIdProvider : WorkerMessageIdProvider {
    /**
     * Produces a random UUID string.
     *
     * @return UUID string suitable for protocol message identifiers.
     */
    override fun nextMessageId(): String = UUID.randomUUID().toString()
}