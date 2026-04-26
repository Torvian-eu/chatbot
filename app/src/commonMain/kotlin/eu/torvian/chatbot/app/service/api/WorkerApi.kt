package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.worker.WorkerDto

/**
 * API client interface for worker management operations.
 *
 * This interface provides methods to interact with the worker endpoints
 * on the server, handling retrieval of workers registered by the current user.
 */
interface WorkerApi {
    /**
     * Retrieves all workers owned by the authenticated user from the server.
     *
     * @return Either [ApiResourceError] if request fails, or List of [WorkerDto] objects.
     */
    suspend fun getMyWorkers(): Either<ApiResourceError, List<WorkerDto>>
}
