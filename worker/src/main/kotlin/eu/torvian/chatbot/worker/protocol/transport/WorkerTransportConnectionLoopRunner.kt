package eu.torvian.chatbot.worker.protocol.transport

import arrow.core.Either
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError

/**
 * Runs the worker transport connection loop for either one-shot or continuous runtime execution.
 */
fun interface WorkerTransportConnectionLoopRunner {
    /**
     * Starts the transport connection loop.
     *
     * @param runOnce When `true`, executes a single connect cycle and returns.
     * @return Either an auth logical error or `Unit` when the loop exits cleanly.
     */
    suspend fun run(runOnce: Boolean): Either<WorkerAuthManagerError, Unit>
}

