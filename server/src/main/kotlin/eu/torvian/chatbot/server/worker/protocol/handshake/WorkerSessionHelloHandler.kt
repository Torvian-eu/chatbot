package eu.torvian.chatbot.server.worker.protocol.handshake

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.sessionWelcome
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.WorkerProtocolCodecError
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolVersion
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionHelloPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionWelcomePayload
import eu.torvian.chatbot.server.worker.session.ConnectedWorkerSession
import eu.torvian.chatbot.server.worker.session.WorkerSessionRegistry
import io.ktor.websocket.CloseReason
import java.util.UUID
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Validates and completes the initial `session.hello` / `session.welcome` worker handshake.
 *
 * The handler performs identity checks against the authenticated worker principal, negotiates a
 * compatible protocol version, and registers the session after the welcome frame is sent.
 *
 * @property registry Live session registry used to expose the negotiated socket to future dispatch.
 */
class WorkerSessionHelloHandler(
    private val registry: WorkerSessionRegistry
) {
    /**
     * Validates a worker hello envelope and finalizes the handshake on success.
     *
     * @param session Live worker session that will receive the welcome frame.
     * @param message Inbound hello envelope.
     * @return `Right(Unit)` when handshake succeeds and the session becomes ready.
     */
    suspend fun handle(
        session: ConnectedWorkerSession,
        message: WorkerProtocolMessage
    ): Either<WorkerSessionHelloError, Unit> = either {
        ensure(message.type == WorkerProtocolMessageTypes.SESSION_HELLO) {
            WorkerSessionHelloError.InvalidMessageType(message.type)
        }

        val hello = message.payload?.let { payload ->
            withError({ error: WorkerProtocolCodecError ->
                val reason = when (error) {
                    is WorkerProtocolCodecError.SerializationFailed -> {
                        error.details ?: "Unable to decode session hello payload"
                    }
                }
                WorkerSessionHelloError.InvalidPayload(reason)
            }) {
                decodeProtocolPayload<WorkerSessionHelloPayload>(payload, "WorkerSessionHelloPayload").bind()
            }
        } ?: raise(WorkerSessionHelloError.MissingPayload)

        ensure(hello.workerUid.isNotBlank()) {
            WorkerSessionHelloError.InvalidPayload("Worker UID must not be blank")
        }
        ensure(hello.supportedProtocolVersions.isNotEmpty()) {
            WorkerSessionHelloError.InvalidPayload("At least one supported protocol version must be advertised")
        }

        ensure(session.workerContext.workerUid == hello.workerUid) {
            WorkerSessionHelloError.IdentityMismatch(
                workerId = session.workerContext.workerId,
                expectedWorkerUid = session.workerContext.workerUid,
                actualWorkerUid = hello.workerUid
            )
        }

        val selectedProtocolVersion = hello.supportedProtocolVersions
            .distinct()
            .firstOrNull { supportedVersion -> supportedVersion in WorkerProtocolVersion.SUPPORTED }
            ?: raise(
                WorkerSessionHelloError.UnsupportedProtocolVersion(
                    advertisedVersions = hello.supportedProtocolVersions.distinct(),
                    supportedVersions = WorkerProtocolVersion.SUPPORTED.toList()
                )
            )

        val acceptedCapabilities = hello.capabilities
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val welcomePayload = WorkerSessionWelcomePayload(
            workerUid = session.workerContext.workerUid,
            selectedProtocolVersion = selectedProtocolVersion,
            acceptedCapabilities = acceptedCapabilities
        )
        val welcomeMessage = sessionWelcome(
            id = UUID.randomUUID().toString(),
            replyTo = message.id,
            interactionId = message.interactionId,
            payload = welcomePayload
        )

        if (!session.send(welcomeMessage)) {
            logger.warn(
                "Failed to send worker welcome frame (workerId={}, workerUid={})",
                session.workerContext.workerId,
                session.workerContext.workerUid
            )
            raise(WorkerSessionHelloError.TransportFailed("Unable to send session welcome"))
        }

        session.markReady(
            selectedProtocolVersion = selectedProtocolVersion,
            acceptedCapabilities = acceptedCapabilities
        )
        val previousSession = registry.register(session)
        if (previousSession != null && previousSession !== session) {
            logger.info(
                "Closing replaced worker session after reconnect (workerId={}, workerUid={})",
                session.workerContext.workerId,
                session.workerContext.workerUid
            )
            previousSession.close(
                CloseReason(
                    CloseReason.Codes.NORMAL,
                    "Replaced by a newer worker connection"
                )
            )
        }

        logger.info(
            "Worker handshake completed successfully (workerId={}, workerUid={}, protocolVersion={}, acceptedCapabilities={})",
            session.workerContext.workerId,
            session.workerContext.workerUid,
            selectedProtocolVersion,
            acceptedCapabilities
        )
    }

    companion object {
        /**
         * Logger used for handshake validation and negotiation diagnostics.
         */
        private val logger: Logger = LogManager.getLogger(WorkerSessionHelloHandler::class.java)
    }
}


