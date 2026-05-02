package eu.torvian.chatbot.worker.runtime

import arrow.core.Either
import eu.torvian.chatbot.worker.mcp.McpClientService
import eu.torvian.chatbot.worker.protocol.transport.TransportConnectionLoopRunner
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [WorkerRuntime] that runs the worker protocol transport connection loop.
 *
 * @property workerUid Worker UID used for logging.
 * @property connectionLoop Transport connection loop that handles auth, session lifecycle, and reconnects.
 * @property mcpClientService MCP runtime service that owns managed MCP client/process resources.
 */
class WorkerRuntimeImpl(
    private val workerUid: String,
    private val connectionLoop: TransportConnectionLoopRunner,
    private val mcpClientService: McpClientService
) : WorkerRuntime {

    /**
     * Ensures runtime shutdown side effects run at most once.
     */
    private val isClosed: AtomicBoolean = AtomicBoolean(false)

    companion object {
        private val logger: Logger = LogManager.getLogger(WorkerRuntimeImpl::class.java)
    }

    override suspend fun run(runOnce: Boolean): Either<WorkerRuntimeError, Unit> {
        logger.info("Starting worker runtime transport loop (workerUid={}, runOnce={})", workerUid, runOnce)
        return connectionLoop.run(runOnce = runOnce)
    }

    /**
     * Releases worker runtime resources and ensures MCP-managed processes are terminated.
     */
    override suspend fun close() {
        if (!isClosed.compareAndSet(false, true)) {
            logger.debug("Worker runtime close skipped because runtime is already closed (workerUid={})", workerUid)
            return
        }

        logger.info("Closing worker runtime resources (workerUid={})", workerUid)
        mcpClientService.close()
        logger.info("Worker runtime resources closed (workerUid={})", workerUid)
    }
}

