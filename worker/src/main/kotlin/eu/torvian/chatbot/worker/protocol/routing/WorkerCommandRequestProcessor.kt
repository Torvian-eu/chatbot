package eu.torvian.chatbot.worker.protocol.routing

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.WorkerProtocolCodecError
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandRejected
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.factory.WorkerInteractionFactory
import eu.torvian.chatbot.worker.protocol.ids.UuidWorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.registry.WorkerActiveInteractionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Routes decoded `command.request` envelopes by command type.
 *
 * This processor owns rejection of malformed command envelopes before any command-specific
 * execution is attempted, then delegates recognized command types to interaction
 * factories that create independently running active interactions.
 *
 * @property interactionScope Coroutine scope used to launch active interactions asynchronously.
 * @property interactionFactoriesByCommandType Interaction factories indexed by command type.
 * @property emitter Outbound protocol emitter used for rejection responses.
 * @property registry Active-interaction registry used to register and route command interactions.
 * @property messageIdProvider Message-ID provider used for emitted rejections.
 */
class WorkerCommandRequestProcessor(
    private val interactionScope: CoroutineScope,
    private val interactionFactoriesByCommandType: Map<String, WorkerInteractionFactory>,
    private val emitter: WorkerOutboundMessageEmitter,
    private val registry: WorkerActiveInteractionRegistry,
    private val messageIdProvider: WorkerMessageIdProvider = UuidWorkerMessageIdProvider()
) : WorkerIncomingMessageProcessor {
    companion object {
        /**
         * Logger used to report unexpected interaction failures.
         */
        private val logger: Logger = LogManager.getLogger(WorkerCommandRequestProcessor::class.java)
    }

    /**
     * Decodes the command-request payload and delegates to the matching command processor.
     *
     * @param message Inbound `command.request` envelope.
     */
    override suspend fun process(message: WorkerProtocolMessage) {
        if (message.type != WorkerProtocolMessageTypes.COMMAND_REQUEST) {
            return
        }

        // interactionId is required by the envelope model; runtime validation only needs to reject blank values.
        val interactionId = message.interactionId

        if (interactionId.isBlank()) {
            emitter.emit(
                rejectedMessage(
                    replyTo = message.id,
                    interactionId = interactionId,
                    commandType = null,
                    reasonCode = WorkerProtocolRejectionReasons.INVALID_FIELD,
                    message = "command.request envelope interactionId must not be blank"
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
                        commandType = null,
                        reasonCode = WorkerProtocolRejectionReasons.MISSING_PAYLOAD,
                        message = "command.request payload is missing"
                    )
                )
                return
            }

        val requestPayload = decodeProtocolPayload<WorkerCommandRequestPayload>(
            payload = payload,
            targetType = "WorkerCommandRequestPayload"
        ).getOrElse { error ->
            emitter.emit(
                rejectedMessage(
                    replyTo = message.id,
                    interactionId = interactionId,
                    commandType = null,
                    reasonCode = WorkerProtocolRejectionReasons.INVALID_COMMAND_PAYLOAD,
                    message = "Unable to decode command.request payload",
                    details = codecErrorDetails(error)
                )
            )
            return
        }

        val interactionFactory = interactionFactoriesByCommandType[requestPayload.commandType]
            ?: run {
                emitter.emit(
                    rejectedMessage(
                        replyTo = message.id,
                        interactionId = interactionId,
                        commandType = requestPayload.commandType,
                        reasonCode = WorkerProtocolRejectionReasons.UNSUPPORTED_COMMAND_TYPE,
                        message = "Unsupported command type '${requestPayload.commandType}'",
                        details = buildJsonObject {
                            put(
                                "supported",
                                buildJsonArray {
                                    interactionFactoriesByCommandType.keys.sorted().forEach { add(JsonPrimitive(it)) }
                                }
                            )
                        }
                    )
                )
                return
            }

        val interaction = interactionFactory.create(
            envelope = message,
            requestPayload = requestPayload,
            emitter = emitter
        )

        if (!registry.register(interaction)) {
            emitter.emit(
                rejectedMessage(
                    replyTo = message.id,
                    interactionId = interactionId,
                    commandType = requestPayload.commandType,
                    reasonCode = WorkerProtocolRejectionReasons.DUPLICATE_INTERACTION_ID,
                    message = "An interaction with id '$interactionId' is already active"
                )
            )
            return
        }

        interactionScope.launch {
            try {
                interaction.start()
            } catch (error: Exception) {
                logger.error(
                    "Active interaction failed unexpectedly (interactionId={}, commandType={})",
                    interactionId,
                    requestPayload.commandType,
                    error
                )
            } finally {
                registry.remove(interactionId)
            }
        }
    }

    /**
     * Builds a `command.rejected` envelope with consistent diagnostic metadata.
     *
     * @param replyTo Envelope identifier that the rejection acknowledges.
     * @param interactionId Known interaction identifier from the envelope.
     * @param commandType Known command type, if any.
     * @param reasonCode Stable machine-readable rejection reason.
     * @param message Human-readable rejection description.
     * @param details Optional structured diagnostics.
     * @return Rejection envelope ready to send.
     */
    private fun rejectedMessage(
        replyTo: String,
        interactionId: String,
        commandType: String?,
        reasonCode: String,
        message: String,
        details: JsonObject? = null
    ): WorkerProtocolMessage {
        return commandRejected(
            id = messageIdProvider.nextMessageId(),
            replyTo = replyTo,
            interactionId = interactionId,
            payload = WorkerCommandRejectedPayload(
                commandType = commandType,
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
