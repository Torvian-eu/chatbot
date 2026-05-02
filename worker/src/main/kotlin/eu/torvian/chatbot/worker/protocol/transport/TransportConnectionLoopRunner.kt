package eu.torvian.chatbot.worker.protocol.transport

import arrow.core.Either
import eu.torvian.chatbot.worker.runtime.WorkerRuntimeError

/**
 * Runs the worker transport connection loop for either one-shot or continuous runtime execution.
 */
fun interface TransportConnectionLoopRunner {
    /**
     * Starts the transport connection loop.
     *
     * Handles all worker startup and connection phases:
     * - Authentication and token management
     * - Assigned configuration bootstrap
     * - WebSocket session lifecycle
     * - Reconnection and backoff on failure
     *
     * @param runOnce When `true`, executes a single connect cycle and returns.
     * @return Either a worker runtime error (auth or bootstrap failure)
     *         or `Unit` when the loop exits cleanly.
     *         In runOnce mode, any startup failure is returned as a Left.
     */
    suspend fun run(runOnce: Boolean): Either<WorkerRuntimeError, Unit>
}

