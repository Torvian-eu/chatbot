package eu.torvian.chatbot.worker.protocol.factory

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.interaction.Interaction

/**
 * Factory that creates an active interaction runtime for a decoded `command.request` payload.
 */
fun interface InteractionFactory {
    /**
     * Creates an active interaction runtime for one request.
     *
     * @param envelope Original inbound `command.request` envelope.
     * @param requestPayload Decoded request payload.
     * @param emitter Outbound protocol emitter used by the interaction.
     * @return Active interaction instance.
     */
    fun create(
        envelope: WorkerProtocolMessage,
        requestPayload: WorkerCommandRequestPayload,
        emitter: OutboundMessageEmitter
    ): Interaction
}
