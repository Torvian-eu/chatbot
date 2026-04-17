package eu.torvian.chatbot.worker.protocol.routing

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandRejected
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.UuidWorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.registry.WorkerActiveInteractionRegistry

/**
 * Routes inbound worker protocol envelopes by envelope message type.
 *
 * @property registry Active-interaction registry used for interaction-first routing by `interactionId`.
 * @property commandRequestProcessor Handler for `command.request` envelopes.
 * @property commandMessageHandler Handler for `command.message` envelopes.
 * @property emitter Outbound protocol emitter used for rejection responses.
 * @property messageIdProvider Message-ID provider used for emitted rejections.
 */
class WorkerProtocolMessageRouter(
    private val registry: WorkerActiveInteractionRegistry,
    private val commandRequestProcessor: WorkerIncomingMessageProcessor,
    private val commandMessageHandler: WorkerCommandMessageHandler,
    private val emitter: WorkerOutboundMessageEmitter,
    private val messageIdProvider: WorkerMessageIdProvider = UuidWorkerMessageIdProvider()
) : WorkerIncomingMessageProcessor {
    /**
     * Dispatches the inbound envelope to the matching envelope-type processor.
     *
     * @param message Inbound worker protocol envelope.
     */
    override suspend fun process(message: WorkerProtocolMessage) {
        if (message.interactionId.isNotBlank()) {
            val activeInteraction = registry.get(message.interactionId)
            if (activeInteraction != null) {
                activeInteraction.onMessage(message)
                return
            }
        }

        when (message.type) {
            WorkerProtocolMessageTypes.COMMAND_REQUEST -> commandRequestProcessor.process(message)
            WorkerProtocolMessageTypes.COMMAND_MESSAGE -> commandMessageHandler.handle(message)
            else -> emitter.emit(
                commandRejected(
                    id = messageIdProvider.nextMessageId(),
                    replyTo = message.id,
                    interactionId = message.interactionId,
                    payload = WorkerCommandRejectedPayload(
                        commandType = null,
                        reasonCode = WorkerProtocolRejectionReasons.UNSUPPORTED_MESSAGE_TYPE,
                        message = "Unsupported message type '${message.type}'"
                    )
                )
            )
        }
    }
}
