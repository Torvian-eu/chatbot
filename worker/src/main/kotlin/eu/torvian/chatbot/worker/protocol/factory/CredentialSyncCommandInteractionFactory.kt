package eu.torvian.chatbot.worker.protocol.factory

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.worker.credential.CredentialSyncCommandExecutor
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.CredentialSyncCommandInteraction
import eu.torvian.chatbot.worker.protocol.interaction.Interaction
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter

/**
 * Interaction factory for credential sync command requests.
 *
 * @property executor Cache-mutation adapter used by created interactions.
 * @property messageIdProvider Message-ID provider used by created interactions.
 */
class CredentialSyncCommandInteractionFactory(
    private val executor: CredentialSyncCommandExecutor,
    private val messageIdProvider: MessageIdProvider = UuidMessageIdProvider(),
) : InteractionFactory {

    override fun create(
        envelope: WorkerProtocolMessage,
        requestPayload: WorkerCommandRequestPayload,
        emitter: OutboundMessageEmitter,
    ): Interaction = CredentialSyncCommandInteraction(
        envelope = envelope,
        requestPayload = requestPayload,
        executor = executor,
        emitter = emitter,
        messageIdProvider = messageIdProvider
    )
}
