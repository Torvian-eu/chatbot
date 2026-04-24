package eu.torvian.chatbot.worker.protocol.routing

import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandRejected
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.worker.protocol.handshake.SessionHandshakeContext
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.registry.InteractionRegistry
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Routes inbound worker protocol envelopes to active interactions first and then to type-specific handlers.
 *
 * The router preserves interaction-first delivery so that handshake messages can reach the active hello interaction
 * before any fallback command routing is applied.
 *
 * @property registry Active-interaction registry used for interaction-first routing by `interactionId`.
 * @property handshakeContext Session-scoped handshake state used to gate command traffic.
 * @property commandRequestProcessor Handler for `command.request` envelopes.
 * @property emitter Outbound protocol emitter used for rejection responses.
 * @property messageIdProvider Message-ID provider used for emitted rejections.
 */
class WorkerProtocolMessageRouter(
    private val registry: InteractionRegistry,
    private val handshakeContext: SessionHandshakeContext,
    private val commandRequestProcessor: IncomingMessageProcessor,
    private val emitter: OutboundMessageEmitter,
    private val messageIdProvider: MessageIdProvider = UuidMessageIdProvider()
) : IncomingMessageProcessor {

    override suspend fun process(message: WorkerProtocolMessage) {
        if (message.interactionId.isBlank()) {
            logger.warn(
                "Rejecting message with blank interaction ID (id={}, type={})",
                message.id,
                message.type
            )
            rejectInvalidInteractionId(message)
            return
        }

        val activeInteraction = registry.get(message.interactionId)
        if (activeInteraction != null) {
            logger.debug(
                "Routing inbound message to active interaction (interactionId={}, type={})",
                message.interactionId,
                message.type
            )
            activeInteraction.onMessage(message)
            return
        }

        when (message.type) {
            WorkerProtocolMessageTypes.SESSION_WELCOME -> {
                logger.warn (
                    "Ignoring session.welcome without an active interaction (interactionId={})",
                    message.interactionId
                )
            }

            WorkerProtocolMessageTypes.COMMAND_REQUEST -> {
                if (!handshakeContext.hasSucceeded()) {
                    logger.warn(
                        "Rejecting command.request before handshake completion (interactionId={})",
                        message.interactionId
                    )
                    rejectHandshakeNotComplete(message)
                    return
                }

                logger.debug(
                    "Routing command.request to command processor (interactionId={})",
                    message.interactionId
                )
                commandRequestProcessor.process(message)
            }

            WorkerProtocolMessageTypes.COMMAND_MESSAGE -> {
                if (!handshakeContext.hasSucceeded()) {
                    logger.warn(
                        "Rejecting command.message before handshake completion (interactionId={})",
                        message.interactionId
                    )
                    rejectHandshakeNotComplete(message)
                    return
                }

                logger.debug(
                    "Rejecting command.message without an active interaction (interactionId={})",
                    message.interactionId
                )
                rejectUnknownInteraction(message)
            }

            else -> {
                logger.warn(
                    "Rejecting unsupported worker protocol message type (type={}, interactionId={})",
                    message.type,
                    message.interactionId
                )
                rejectUnsupportedMessageType(message)
            }
        }
    }

    private suspend fun rejectInvalidInteractionId(message: WorkerProtocolMessage) {
        emitter.emit(
            commandRejected(
                id = messageIdProvider.nextMessageId(),
                replyTo = message.id,
                interactionId = message.interactionId,
                payload = WorkerCommandRejectedPayload(
                    commandType = null,
                    reasonCode = WorkerProtocolRejectionReasons.INVALID_INTERACTION_ID,
                    message = "Invalid interaction ID format: '${message.interactionId}'"
                )
            )
        )
    }

    /**
     * Emits a handshake-not-complete rejection for command traffic received before hello success.
     *
     * @param message Rejected inbound envelope.
     */
    private suspend fun rejectHandshakeNotComplete(message: WorkerProtocolMessage) {
        emitter.emit(
            commandRejected(
                id = messageIdProvider.nextMessageId(),
                replyTo = message.id,
                interactionId = message.interactionId,
                payload = WorkerCommandRejectedPayload(
                    commandType = null,
                    reasonCode = WorkerProtocolRejectionReasons.HANDSHAKE_NOT_COMPLETE,
                    message = "Session handshake is not complete"
                )
            )
        )
    }

    /**
     * Emits an unknown-interaction rejection for command messages that have no active interaction.
     *
     * @param message Rejected inbound envelope.
     */
    private suspend fun rejectUnknownInteraction(message: WorkerProtocolMessage) {
        emitter.emit(
            commandRejected(
                id = messageIdProvider.nextMessageId(),
                replyTo = message.id,
                interactionId = message.interactionId,
                payload = WorkerCommandRejectedPayload(
                    commandType = null,
                    reasonCode = WorkerProtocolRejectionReasons.UNKNOWN_INTERACTION_ID,
                    message = "No active interaction found for interaction ID '${message.interactionId}'"
                )
            )
        )
    }

    /**
     * Emits an unsupported-message rejection for message types the worker does not handle.
     *
     * @param message Rejected inbound envelope.
     */
    private suspend fun rejectUnsupportedMessageType(message: WorkerProtocolMessage) {
        emitter.emit(
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

    companion object {
        /**
         * Logger instance for the router class.
         */
        private val logger: Logger = LogManager.getLogger(WorkerProtocolMessageRouter::class.java)
    }
}
