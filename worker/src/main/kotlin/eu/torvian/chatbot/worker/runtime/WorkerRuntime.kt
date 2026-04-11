package eu.torvian.chatbot.worker.runtime

import arrow.core.Either
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError

/**
 * Orchestrates worker runtime behavior after process bootstrap has completed.
 */
interface WorkerRuntime {

    /**
     * Runs the main worker loop, performing authentication and other startup tasks as needed.
     *
     * @param runOnce If `true`, performs a single authentication attempt and exits; otherwise, runs indefinitely.
     * @return Either a logical runtime error or `Unit` on successful completion.
     */
    suspend fun run(runOnce: Boolean): Either<WorkerAuthManagerError, Unit>
}

