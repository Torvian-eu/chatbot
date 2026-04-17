package eu.torvian.chatbot.worker.protocol.transport

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage

/**
 * Emits worker protocol envelopes to the outbound transport.
 */
fun interface WorkerOutboundMessageEmitter {
    /**
     * Emits one outbound protocol envelope.
     *
     * @param message Outbound envelope to send.
     */
    suspend fun emit(message: WorkerProtocolMessage)
}
