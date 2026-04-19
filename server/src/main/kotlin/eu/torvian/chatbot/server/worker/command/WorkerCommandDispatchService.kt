package eu.torvian.chatbot.server.worker.command

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import kotlin.time.Duration

/**
 * Dispatches server-originated worker commands to a connected worker session.
 *
 * Implementations are responsible for creating a correlation identifier, sending the outbound
 * `command.request` envelope, and suspending until a terminal lifecycle message arrives or the
 * request times out.
 */
interface WorkerCommandDispatchService {
    /**
     * Sends a command request to the specified worker and waits for the final lifecycle outcome.
     *
     * @param workerId Worker identifier resolved from the authenticated worker token.
     * @param commandRequestPayload Structured command payload carried inside `command.request`.
     * @param timeout Maximum time to wait for a terminal command outcome.
     * @return Either dispatch failure or completed command result.
     */
    suspend fun dispatch(
        workerId: Long,
        commandRequestPayload: WorkerCommandRequestPayload,
        timeout: Duration = defaultTimeout
    ): Either<WorkerCommandDispatchError, WorkerCommandDispatchSuccess>

    /**
     * Default maximum wait time used when callers do not provide an explicit timeout.
     */
    val defaultTimeout: Duration
}

