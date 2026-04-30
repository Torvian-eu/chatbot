package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.worker.WorkerDto

/**
 * API client interface for worker management operations.
 *
 * This interface provides methods to interact with the worker endpoints
 * on the server, handling retrieval, update, and deletion of workers
 * registered by the current user.
 */
interface WorkerApi {
    /**
     * Retrieves all workers owned by the authenticated user from the server.
     *
     * Corresponds to `GET /api/v1/workers`.
     *
     * @return Either [ApiResourceError] if request fails, or List of [WorkerDto] objects.
     */
    suspend fun getMyWorkers(): Either<ApiResourceError, List<WorkerDto>>

    /**
     * Updates an existing worker's metadata.
     *
     * Corresponds to `PATCH /api/v1/workers/{id}`.
     *
     * @param id The unique identifier of the worker to update.
     * @param displayName New display name for the worker.
     * @param allowedScopes Updated list of allowed scopes for the worker.
     * @return Either [ApiResourceError] if request fails, or the updated [WorkerDto] on success.
     */
    suspend fun updateWorker(
        id: Long,
        displayName: String,
        allowedScopes: List<String>
    ): Either<ApiResourceError, WorkerDto>

    /**
     * Deletes a worker owned by the authenticated user.
     *
     * Corresponds to `DELETE /api/v1/workers/{id}`.
     *
     * @param id The unique identifier of the worker to delete.
     * @return Either [ApiResourceError] if request fails, or Unit on success.
     */
    suspend fun deleteWorker(id: Long): Either<ApiResourceError, Unit>
}
