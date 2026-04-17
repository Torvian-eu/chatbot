package eu.torvian.chatbot.worker.runtime

import arrow.core.Either
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError
import eu.torvian.chatbot.worker.protocol.transport.WorkerTransportConnectionLoopRunner
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default [WorkerRuntime] that runs the worker protocol transport connection loop.
 *
 * @property workerUid Worker UID used for logging.
 * @property connectionLoop Transport connection loop that handles auth, session lifecycle, and reconnects.
 */
class WorkerRuntimeImpl(
    private val workerUid: String,
    private val connectionLoop: WorkerTransportConnectionLoopRunner
) : WorkerRuntime {

    companion object {
        private val logger: Logger = LogManager.getLogger(WorkerRuntimeImpl::class.java)
    }

    override suspend fun run(runOnce: Boolean): Either<WorkerAuthManagerError, Unit> {
        logger.info("Starting worker runtime transport loop (workerUid={}, runOnce={})", workerUid, runOnce)
        return connectionLoop.run(runOnce = runOnce)
    }
}

