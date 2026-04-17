package eu.torvian.chatbot.worker.protocol.factory

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.UuidWorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.WorkerActiveInteraction
import eu.torvian.chatbot.worker.protocol.interaction.WorkerToolCallInteraction

/**
 * Interaction factory for direct `tool.call` command requests.
 *
 * @property messageIdProvider Message-ID provider used by created interactions.
 */
class WorkerToolCallInteractionFactory(
    private val messageIdProvider: WorkerMessageIdProvider = UuidWorkerMessageIdProvider()
) : WorkerInteractionFactory {
    /**
     * @param envelope Original inbound `command.request` envelope.
     * @param requestPayload Decoded request payload.
     * @param emitter Outbound protocol emitter used by the created interaction.
     * @return New direct tool-call interaction.
     */
    override fun create(
        envelope: WorkerProtocolMessage,
        requestPayload: WorkerCommandRequestPayload,
        emitter: WorkerOutboundMessageEmitter
    ): WorkerActiveInteraction {
        return WorkerToolCallInteraction(
            envelope = envelope,
            requestPayload = requestPayload,
            emitter = emitter,
            messageIdProvider = messageIdProvider
        )
    }
}
