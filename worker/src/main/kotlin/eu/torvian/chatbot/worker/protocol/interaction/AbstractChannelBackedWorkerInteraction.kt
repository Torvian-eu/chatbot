package eu.torvian.chatbot.worker.protocol.interaction

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import kotlinx.coroutines.channels.Channel

/**
 * Base implementation for active interactions that buffer inbound protocol envelopes in a channel.
 *
 * @property interactionId Stable interaction identifier for this interaction.
 * @property emitter Outbound protocol emitter used by the interaction.
 */
abstract class AbstractChannelBackedWorkerInteraction(
    final override val interactionId: String,
    protected val emitter: WorkerOutboundMessageEmitter
) : WorkerActiveInteraction {
    /**
     * Inbound message channel that stores full protocol envelopes addressed to this interaction.
     */
    protected val inboundMessages: Channel<WorkerProtocolMessage> = Channel(Channel.UNLIMITED)

    /**
     * @param message Inbound protocol envelope addressed to this interaction.
     */
    final override suspend fun onMessage(message: WorkerProtocolMessage) {
        inboundMessages.send(message)
    }

    /**
     * Awaits the next protocol envelope addressed to this active interaction.
     *
     * @return Next inbound protocol envelope.
     */
    protected suspend fun awaitNextMessage(): WorkerProtocolMessage {
        return inboundMessages.receive()
    }
}
