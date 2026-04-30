package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.worker.WorkerDto
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for worker management with reactive data streams.
 *
 * This repository provides worker list operations with StateFlow-based reactive updates,
 * allowing ViewModels to automatically react to worker data changes. It follows the
 * same pattern as other repositories in the application for consistency.
 */
interface WorkerRepository {
    /**
     * Reactive stream of workers owned by the authenticated user.
     *
     * This StateFlow provides real-time updates whenever worker data changes,
     * allowing ViewModels to automatically react to worker changes without
     * manual polling or refresh operations.
     *
     * @return StateFlow containing the current state of worker data loading.
     */
    val workers: StateFlow<DataState<RepositoryError, List<WorkerDto>>>

    /**
     * Loads all workers from the server and updates the StateFlow.
     *
     * This method fetches the latest worker data from the backend and updates
     * the reactive stream. It should be called when fresh data is needed.
     *
     * @return Either [RepositoryError] if loading fails, or Unit on success.
     */
    suspend fun loadWorkers(): Either<RepositoryError, Unit>

    /**
     * Updates an existing worker's metadata and refreshes the worker list.
     *
     * This method calls the API to update the worker and, upon success,
     * automatically refreshes the worker list to reflect the changes.
     *
     * @param id The unique identifier of the worker to update.
     * @param displayName New display name for the worker.
     * @param allowedScopes Updated list of allowed scopes for the worker.
     * @return Either [RepositoryError] if update fails, or Unit on success.
     */
    suspend fun updateWorker(
        id: Long,
        displayName: String,
        allowedScopes: List<String>
    ): Either<RepositoryError, Unit>

    /**
     * Deletes a worker and refreshes the worker list.
     *
     * This method calls the API to delete the worker and, upon success,
     * automatically refreshes the worker list to reflect the removal.
     *
     * @param id The unique identifier of the worker to delete.
     * @return Either [RepositoryError] if deletion fails, or Unit on success.
     */
    suspend fun deleteWorker(id: Long): Either<RepositoryError, Unit>
}
