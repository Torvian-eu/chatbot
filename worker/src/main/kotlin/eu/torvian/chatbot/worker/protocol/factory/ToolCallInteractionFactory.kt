package eu.torvian.chatbot.worker.protocol.factory

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.Interaction
import eu.torvian.chatbot.worker.protocol.interaction.ToolCallInteraction

/**
 * Interaction factory for direct `tool.call` command requests.
 *
 * @property messageIdProvider Message-ID provider used by created interactions.
 */
class ToolCallInteractionFactory(
    private val messageIdProvider: MessageIdProvider = UuidMessageIdProvider()
) : InteractionFactory {

    override fun create(
        envelope: WorkerProtocolMessage,
        requestPayload: WorkerCommandRequestPayload,
        emitter: OutboundMessageEmitter
    ): Interaction {
        return ToolCallInteraction(
            envelope = envelope,
            requestPayload = requestPayload,
            emitter = emitter,
            messageIdProvider = messageIdProvider
        )
    }
}
