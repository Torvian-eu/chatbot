package eu.torvian.chatbot.worker.runtime

import arrow.core.Either

/**
 * Orchestrates worker runtime behavior after process bootstrap has completed.
 */
interface WorkerRuntime {

    /**
     * Runs the main worker loop, performing authentication and other startup tasks as needed.
     *
     * Handles:
     * - Worker authentication and token lifecycle
     * - Assigned configuration bootstrap
     * - WebSocket session management
     * - Reconnection with backoff
     *
     * @param runOnce If `true`, performs a single authentication attempt and exits; otherwise, runs indefinitely.
     * @return Either a worker runtime error (auth or bootstrap failure) or `Unit` on successful completion.
     *         In runOnce mode, any startup failure is returned as a Left.
     */
    suspend fun run(runOnce: Boolean): Either<WorkerRuntimeError, Unit>

    /**
     * Releases runtime-owned resources during worker shutdown.
     *
     * Implementations should be idempotent because shutdown may be triggered by multiple
     * lifecycle paths (for example, normal completion and error handling).
     */
    suspend fun close()
}

