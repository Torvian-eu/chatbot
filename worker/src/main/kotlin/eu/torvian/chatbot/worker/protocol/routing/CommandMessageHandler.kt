package eu.torvian.chatbot.worker.protocol.routing

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandMessagePayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.WorkerProtocolCodecError
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandRejected
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.registry.InteractionRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Handles inbound `command.message` envelopes and routes them to active interactions.
 *
 * @property registry Active-interaction registry used for interaction lookup.
 * @property emitter Outbound protocol emitter used for rejection responses.
 * @property messageIdProvider Message-ID provider used for emitted rejections.
 */
class CommandMessageHandler(
    private val registry: InteractionRegistry,
    private val emitter: OutboundMessageEmitter,
    private val messageIdProvider: MessageIdProvider = UuidMessageIdProvider()
) {
    /**
     * Decodes and routes one inbound `command.message` envelope.
     *
     * @param message Inbound protocol envelope.
     */
    suspend fun handle(message: WorkerProtocolMessage) {
        // interactionId is required by the envelope model; runtime validation only needs to reject blank values.
        val interactionId = message.interactionId

        if (interactionId.isBlank()) {
            emitter.emit(
                rejectedMessage(
                    replyTo = message.id,
                    interactionId = interactionId,
                    reasonCode = WorkerProtocolRejectionReasons.INVALID_FIELD,
                    message = "command.message envelope interactionId must not be blank"
                )
            )
            return
        }

        val payload = message.payload
            ?: run {
                emitter.emit(
                    rejectedMessage(
                        replyTo = message.id,
                        interactionId = interactionId,
                        reasonCode = WorkerProtocolRejectionReasons.MISSING_PAYLOAD,
                        message = "command.message payload is missing"
                    )
                )
                return
            }

        decodeProtocolPayload<WorkerCommandMessagePayload>(
            payload = payload,
            targetType = "WorkerCommandMessagePayload"
        ).getOrElse { error ->
            emitter.emit(
                rejectedMessage(
                    replyTo = message.id,
                    interactionId = interactionId,
                    reasonCode = WorkerProtocolRejectionReasons.INVALID_FIELD,
                    message = "Unable to decode command.message payload",
                    details = codecErrorDetails(error)
                )
            )
            return
        }

        val interaction = registry.get(interactionId)
        if (interaction == null) {
            emitter.emit(
                rejectedMessage(
                    replyTo = message.id,
                    interactionId = interactionId,
                    reasonCode = WorkerProtocolRejectionReasons.UNKNOWN_INTERACTION_ID,
                    message = "No active interaction found for interactionId '$interactionId'"
                )
            )
            return
        }

        // Forward the entire envelope so active interactions can inspect transport metadata and future fields.
        interaction.onMessage(message)
    }

    /**
     * Builds a `command.rejected` envelope with consistent diagnostic metadata.
     *
     * @param replyTo Envelope identifier that the rejection acknowledges.
     * @param interactionId Known interaction identifier from the envelope.
     * @param reasonCode Stable machine-readable rejection reason.
     * @param message Human-readable rejection description.
     * @param details Optional structured diagnostics.
     * @return Rejection envelope ready to send.
     */
    private fun rejectedMessage(
        replyTo: String,
        interactionId: String,
        reasonCode: String,
        message: String,
        details: JsonObject? = null
    ): WorkerProtocolMessage {
        return commandRejected(
            id = messageIdProvider.nextMessageId(),
            replyTo = replyTo,
            interactionId = interactionId,
            payload = WorkerCommandRejectedPayload(
                commandType = null,
                reasonCode = reasonCode,
                message = message,
                details = details
            )
        )
    }

    /**
     * Converts codec failures into protocol-friendly diagnostic JSON.
     *
     * @param error Generic codec failure raised by the shared payload codec.
     * @return Structured details for a rejection payload.
     */
    private fun codecErrorDetails(error: WorkerProtocolCodecError): JsonObject = buildJsonObject {
        when (error) {
            is WorkerProtocolCodecError.SerializationFailed -> {
                put("error", "serialization_failed")
                put("operation", error.operation)
                put("targetType", error.targetType)
                error.details?.let { put("details", it) }
            }
        }
    }
}


