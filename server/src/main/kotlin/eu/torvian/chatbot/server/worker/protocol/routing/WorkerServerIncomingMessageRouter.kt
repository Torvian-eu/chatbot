package eu.torvian.chatbot.server.worker.protocol.routing

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.WorkerProtocolCodecError
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandAcceptedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchResult
import eu.torvian.chatbot.server.worker.command.pending.PendingWorkerCommand
import eu.torvian.chatbot.server.worker.command.pending.PendingWorkerCommandRegistry
import eu.torvian.chatbot.server.worker.protocol.handshake.WorkerSessionHelloHandler
import eu.torvian.chatbot.server.worker.session.ConnectedWorkerSession
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Dispatches decoded worker protocol messages to the appropriate server-side handler.
 *
 * The router intentionally supports the initial hello flow now and leaves the command lifecycle
 * branches as first-class paths for dispatch correlation.
 *
 * @property helloHandler Handler that validates and completes `session.hello`.
 * @property pendingCommandRegistry Registry used to correlate `command.*` lifecycle frames.
 */
class WorkerServerIncomingMessageRouter(
    private val helloHandler: WorkerSessionHelloHandler,
    private val pendingCommandRegistry: PendingWorkerCommandRegistry
) {
    /**
     * Routes one decoded worker protocol message.
     *
     * @param session Live worker session that owns the inbound message stream.
     * @param message Decoded worker protocol envelope.
     */
    suspend fun route(session: ConnectedWorkerSession, message: WorkerProtocolMessage) {
        if (!session.isReady() && message.type != WorkerProtocolMessageTypes.SESSION_HELLO) {
            logger.warn(
                "Rejecting non-hello message before worker handshake completed (workerId={}, type={})",
                session.workerContext.workerId,
                message.type
            )
            session.closeProtocolViolation("Worker hello handshake required before protocol messages")
            return
        }

        when (message.type) {
            WorkerProtocolMessageTypes.SESSION_HELLO -> handleHello(session, message)
            WorkerProtocolMessageTypes.COMMAND_ACCEPTED -> handleCommandAccepted(session, message)
            WorkerProtocolMessageTypes.COMMAND_RESULT -> handleCommandResult(session, message)
            WorkerProtocolMessageTypes.COMMAND_REJECTED -> handleCommandRejected(session, message)
            WorkerProtocolMessageTypes.COMMAND_MESSAGE -> logPlaceholder(session, message)
            WorkerProtocolMessageTypes.HEARTBEAT_PING -> logPlaceholder(session, message)
            WorkerProtocolMessageTypes.HEARTBEAT_PONG -> logPlaceholder(session, message)
            else -> logger.debug(
                "Ignoring unsupported worker protocol message type (workerId={}, type={})",
                session.workerContext.workerId,
                message.type
            )
        }
    }

    private suspend fun handleHello(session: ConnectedWorkerSession, message: WorkerProtocolMessage) {
        if (session.isReady()) {
            logger.debug(
                "Ignoring duplicate worker hello after handshake completion (workerId={}, messageId={})",
                session.workerContext.workerId,
                message.id
            )
            return
        }

        when (val result = helloHandler.handle(session, message)) {
            is Either.Left -> {
                logger.warn(
                    "Worker hello handshake failed (workerId={}, error={})",
                    session.workerContext.workerId,
                    result.value
                )
                session.closeProtocolViolation("Worker hello handshake failed")
            }

            is Either.Right -> {
                // The hello handler already sent the welcome frame and registered the live session.
            }
        }
    }

    /**
     * Handles inbound `command.accepted` frames by recording that the worker acknowledged the request.
     *
     * @param session Live worker session that owns the inbound message stream.
     * @param message Inbound accepted envelope.
     */
    private fun handleCommandAccepted(session: ConnectedWorkerSession, message: WorkerProtocolMessage) {
        val pendingCommand = pendingCommandRegistry.markAccepted(message.interactionId)
        if (pendingCommand == null) {
            logger.debug(
                "Ignoring command.accepted for unknown interaction (workerId={}, interactionId={}, messageId={})",
                session.workerContext.workerId,
                message.interactionId,
                message.id
            )
            return
        }

        when (message.payload) {
            null -> {
                logger.warn(
                    "Received command.accepted without payload for active interaction (workerId={}, interactionId={}, commandType={})",
                    session.workerContext.workerId,
                    message.interactionId,
                    pendingCommand.commandType
                )
                completeMalformedLifecycle(session, pendingCommand, message, "Missing command accepted payload")
            }

            else -> {
                val acceptedPayload = requireNotNull(message.payload)
                when (val decoded = decodeProtocolPayload<WorkerCommandAcceptedPayload>(
                    acceptedPayload,
                    "WorkerCommandAcceptedPayload"
                )) {
                    is Either.Left -> handleMalformedLifecyclePayload(session, pendingCommand, message, decoded.value)
                    is Either.Right -> logger.info(
                        "Worker accepted command request (workerId={}, interactionId={}, commandType={})",
                        session.workerContext.workerId,
                        message.interactionId,
                        pendingCommand.commandType
                    )
                }
            }
        }
    }

    /**
     * Handles inbound `command.result` frames by completing the matching pending dispatch.
     *
     * @param session Live worker session that owns the inbound message stream.
     * @param message Inbound result envelope.
     */
    private fun handleCommandResult(session: ConnectedWorkerSession, message: WorkerProtocolMessage) {
        val pendingCommand = pendingCommandRegistry.get(message.interactionId)
        if (pendingCommand == null) {
            logger.debug(
                "Ignoring command.result for unknown interaction (workerId={}, interactionId={}, messageId={})",
                session.workerContext.workerId,
                message.interactionId,
                message.id
            )
            return
        }

        val payload = message.payload
        if (payload == null) {
            logger.warn(
                "Received command.result without payload for active interaction (workerId={}, interactionId={}, commandType={})",
                session.workerContext.workerId,
                message.interactionId,
                pendingCommand.commandType
            )
            completeMalformedLifecycle(session, pendingCommand, message, "Missing command result payload")
            return
        }

        when (val decoded = decodeProtocolPayload<WorkerCommandResultPayload>(payload, "WorkerCommandResultPayload")) {
            is Either.Left -> handleMalformedLifecyclePayload(session, pendingCommand, message, decoded.value)
            is Either.Right -> {
                val completed = pendingCommandRegistry.complete(
                    interactionId = message.interactionId,
                    outcome = WorkerCommandDispatchResult.Completed(
                        workerId = session.workerContext.workerId,
                        interactionId = message.interactionId,
                        commandType = pendingCommand.commandType,
                        result = decoded.value
                    )
                )
                if (completed) {
                    logger.info(
                        "Worker completed command request (workerId={}, interactionId={}, commandType={}, status={})",
                        session.workerContext.workerId,
                        message.interactionId,
                        pendingCommand.commandType,
                        decoded.value.status
                    )
                }
            }
        }
    }

    /**
     * Handles inbound `command.rejected` frames by completing the matching pending dispatch.
     *
     * @param session Live worker session that owns the inbound message stream.
     * @param message Inbound rejection envelope.
     */
    private fun handleCommandRejected(session: ConnectedWorkerSession, message: WorkerProtocolMessage) {
        val pendingCommand = pendingCommandRegistry.get(message.interactionId)
        if (pendingCommand == null) {
            logger.debug(
                "Ignoring command.rejected for unknown interaction (workerId={}, interactionId={}, messageId={})",
                session.workerContext.workerId,
                message.interactionId,
                message.id
            )
            return
        }

        val payload = message.payload
        if (payload == null) {
            logger.warn(
                "Received command.rejected without payload for active interaction (workerId={}, interactionId={}, commandType={})",
                session.workerContext.workerId,
                message.interactionId,
                pendingCommand.commandType
            )
            completeMalformedLifecycle(session, pendingCommand, message, "Missing command rejection payload")
            return
        }

        when (val decoded =
            decodeProtocolPayload<WorkerCommandRejectedPayload>(payload, "WorkerCommandRejectedPayload")) {
            is Either.Left -> handleMalformedLifecyclePayload(session, pendingCommand, message, decoded.value)
            is Either.Right -> {
                val completed = pendingCommandRegistry.complete(
                    interactionId = message.interactionId,
                    outcome = WorkerCommandDispatchResult.Rejected(
                        workerId = session.workerContext.workerId,
                        interactionId = message.interactionId,
                        commandType = pendingCommand.commandType,
                        rejection = decoded.value
                    )
                )
                if (completed) {
                    logger.info(
                        "Worker rejected command request (workerId={}, interactionId={}, commandType={}, reasonCode={})",
                        session.workerContext.workerId,
                        message.interactionId,
                        pendingCommand.commandType,
                        decoded.value.reasonCode
                    )
                }
            }
        }
    }

    /**
     * Completes a pending command with a malformed-lifecycle outcome and logs the decode failure.
     *
     * @param session Live worker session that owns the inbound message stream.
     * @param pendingCommand Pending command that matched the inbound interaction identifier.
     * @param message Inbound lifecycle envelope that failed to decode.
     * @param error The decode failure returned by the shared protocol codec.
     */
    private fun handleMalformedLifecyclePayload(
        session: ConnectedWorkerSession,
        pendingCommand: PendingWorkerCommand,
        message: WorkerProtocolMessage,
        error: WorkerProtocolCodecError
    ) {
        val reason = when (error) {
            is WorkerProtocolCodecError.SerializationFailed -> error.details ?: "serialization failed"
        }
        logger.warn(
            "Malformed worker command lifecycle payload (workerId={}, interactionId={}, commandType={}, messageType={}, reason={})",
            session.workerContext.workerId,
            message.interactionId,
            pendingCommand.commandType,
            message.type,
            reason
        )
        completeMalformedLifecycle(session, pendingCommand, message, reason)
    }

    /**
     * Completes the pending interaction with a malformed-lifecycle outcome.
     *
     * @param session Live worker session that owns the inbound message stream.
     * @param pendingCommand Pending command that matched the inbound interaction identifier.
     * @param message Inbound lifecycle envelope that could not be processed.
     * @param reason Human-readable failure description.
     */
    private fun completeMalformedLifecycle(
        session: ConnectedWorkerSession,
        pendingCommand: PendingWorkerCommand,
        message: WorkerProtocolMessage,
        reason: String
    ) {
        pendingCommandRegistry.complete(
            interactionId = message.interactionId,
            outcome = WorkerCommandDispatchResult.MalformedLifecyclePayload(
                workerId = session.workerContext.workerId,
                interactionId = message.interactionId,
                commandType = pendingCommand.commandType,
                messageType = message.type,
                reason = reason
            )
        )
    }

    private fun logPlaceholder(session: ConnectedWorkerSession, message: WorkerProtocolMessage) {
        logger.debug(
            "Received worker protocol message reserved for future handling (workerId={}, type={}, messageId={})",
            session.workerContext.workerId,
            message.type,
            message.id
        )
    }

    companion object {
        /**
         * Logger used for inbound worker protocol dispatch diagnostics.
         */
        private val logger: Logger = LogManager.getLogger(WorkerServerIncomingMessageRouter::class.java)
    }
}


