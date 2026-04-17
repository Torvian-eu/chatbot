package eu.torvian.chatbot.worker.protocol.interaction

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandRejected
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.UuidWorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider

/**
 * Active interaction that rejects unimplemented direct `tool.call` requests.
 *
 * @property envelope Original inbound `command.request` envelope.
 * @property requestPayload Decoded command-request payload for this interaction.
 * @property emitter Outbound protocol emitter used for lifecycle responses.
 * @property messageIdProvider Message-ID provider used for outbound envelopes.
 */
class WorkerToolCallInteraction(
    private val envelope: WorkerProtocolMessage,
    private val requestPayload: WorkerCommandRequestPayload,
    emitter: WorkerOutboundMessageEmitter,
    private val messageIdProvider: WorkerMessageIdProvider = UuidWorkerMessageIdProvider()
) : AbstractChannelBackedWorkerInteraction(
    interactionId = envelope.interactionId,
    emitter = emitter
) {
    /**
     * Emits one `command.rejected` response because direct `tool.call` is not implemented yet.
     */
    override suspend fun start() {
        emitter.emit(
            commandRejected(
                id = messageIdProvider.nextMessageId(),
                replyTo = envelope.id,
                interactionId = interactionId,
                payload = WorkerCommandRejectedPayload(
                    commandType = WorkerProtocolCommandTypes.TOOL_CALL,
                    reasonCode = WorkerProtocolRejectionReasons.NOT_IMPLEMENTED,
                    message = "Direct tool.call is not implemented yet"
                )
            )
        )
    }
}
