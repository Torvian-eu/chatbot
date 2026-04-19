package eu.torvian.chatbot.server.worker.command

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.commandRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.server.worker.command.pending.PendingWorkerCommand
import eu.torvian.chatbot.server.worker.command.pending.PendingWorkerCommandRegistry
import eu.torvian.chatbot.server.worker.session.WorkerSessionRegistry
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default in-memory worker command dispatcher.
 *
 * The dispatcher resolves the current live worker session, registers the interaction in the
 * pending-command registry, sends the outbound `command.request` envelope, and waits for a final
 * lifecycle outcome.
 *
 * If the worker disconnects before completion, the dispatcher returns an explicit disconnect
 * outcome rather than waiting for the timeout.
 *
 * @property workerSessionRegistry Registry used to look up the currently connected worker session.
 * @property pendingCommandRegistry Registry used to correlate inbound lifecycle frames.
 * @property defaultTimeout Default wait time used when the caller does not override the timeout.
 */
class DefaultWorkerCommandDispatchService(
    private val workerSessionRegistry: WorkerSessionRegistry,
    private val pendingCommandRegistry: PendingWorkerCommandRegistry,
    override val defaultTimeout: Duration = 30.seconds
) : WorkerCommandDispatchService {
    override suspend fun dispatch(
        workerId: Long,
        commandRequestPayload: WorkerCommandRequestPayload,
        timeout: Duration
    ): Either<WorkerCommandDispatchError, WorkerCommandDispatchSuccess> = either {
        val session = workerSessionRegistry.get(workerId)
            ?: raise(WorkerCommandDispatchError.WorkerNotConnected(workerId))

        if (!session.isReady()) {
            logger.warn(
                "Refusing to dispatch command to worker session that is not ready yet (workerId={}, commandType={})",
                workerId,
                commandRequestPayload.commandType
            )
            raise(WorkerCommandDispatchError.WorkerNotConnected(workerId))
        }

        val interactionId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()
        val pendingCommand = PendingWorkerCommand(
            workerId = workerId,
            interactionId = interactionId,
            messageId = messageId,
            commandType = commandRequestPayload.commandType
        )

        if (!pendingCommandRegistry.register(pendingCommand)) {
            logger.warn(
                "Rejected worker command dispatch because interaction ID was already registered (workerId={}, interactionId={}, commandType={})",
                workerId,
                interactionId,
                commandRequestPayload.commandType
            )
            raise(WorkerCommandDispatchError.DuplicateInteractionId(interactionId))
        }

        logger.info(
            "Dispatching worker command request (workerId={}, interactionId={}, commandType={})",
            workerId,
            interactionId,
            commandRequestPayload.commandType
        )

        val outboundMessage = commandRequest(
            id = messageId,
            interactionId = interactionId,
            payload = commandRequestPayload
        )

        val sent = try {
            session.send(outboundMessage)
        } catch (exception: Exception) {
            logger.warn(
                "Failed to send worker command request (workerId={}, interactionId={}, commandType={})",
                workerId,
                interactionId,
                commandRequestPayload.commandType,
                exception
            )
            false
        }

        if (!sent) {
            pendingCommandRegistry.remove(interactionId)
            val error = WorkerCommandDispatchError.SessionDisconnected(
                workerId = workerId,
                interactionId = interactionId,
                commandType = commandRequestPayload.commandType,
                reason = "Worker session closed before the command request could be written"
            )
            logger.warn(
                "Worker session became unavailable while dispatching command (workerId={}, interactionId={}, commandType={})",
                workerId,
                interactionId,
                commandRequestPayload.commandType
            )
            raise(error)
        }

        val finalOutcome = withTimeoutOrNull(timeout) {
            pendingCommand.completion.await()
        }

        if (finalOutcome != null) {
            logger.info(
                "Worker command dispatch completed (workerId={}, interactionId={}, commandType={}, outcome={})",
                workerId,
                interactionId,
                commandRequestPayload.commandType,
                finalOutcome::class.simpleName
            )
            finalOutcome.bind()
        } else {
            val timeoutError = WorkerCommandDispatchError.TimedOut(
                workerId = workerId,
                interactionId = interactionId,
                commandType = commandRequestPayload.commandType,
                timeout = timeout
            )
            pendingCommandRegistry.complete(interactionId, timeoutError.left())
            logger.warn(
                "Worker command dispatch timed out (workerId={}, interactionId={}, commandType={}, timeout={})",
                workerId,
                interactionId,
                commandRequestPayload.commandType,
                timeout
            )
            raise(timeoutError)
        }
    }

    companion object {
        /**
         * Logger used for outbound command dispatch diagnostics.
         */
        private val logger: Logger = LogManager.getLogger(DefaultWorkerCommandDispatchService::class.java)
    }
}


