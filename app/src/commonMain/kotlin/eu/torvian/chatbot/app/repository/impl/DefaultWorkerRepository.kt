package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.service.api.WorkerApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.worker.WorkerDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [WorkerRepository] providing worker management with reactive StateFlow updates.
 *
 * This implementation maintains an in-memory cache of workers using StateFlow, automatically
 * refreshing the cache after successful load operations. It follows the same patterns
 * as other repositories in the application for consistency.
 *
 * @property workerApi The API client for worker-related operations.
 * @property toolRepository The shared tool repository whose cache backs the Configure Tools dialog.
 *   It is refreshed after a prefix change so renamed built-in tool public names appear immediately.
 * @property builtInToolRepository The built-in tool repository whose per-worker cache backs the
 *   Built-in Tools tab. It is refreshed after a prefix change so the Edit Tool dialog shows the
 *   renamed public names immediately.
 */
class DefaultWorkerRepository(
    private val workerApi: WorkerApi,
    private val toolRepository: ToolRepository,
    private val builtInToolRepository: BuiltInToolRepository
) : WorkerRepository {

    companion object {
        private val logger = kmpLogger<DefaultWorkerRepository>()
    }

    private val _workers = MutableStateFlow<DataState<RepositoryError, List<WorkerDto>>>(DataState.Idle)
    override val workers: StateFlow<DataState<RepositoryError, List<WorkerDto>>> = _workers.asStateFlow()

    override suspend fun loadWorkers(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_workers.value.isLoading) return Unit.right()

        _workers.update { DataState.Loading }

        return workerApi.getMyWorkers().fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load workers")
                logger.warn("Failed to load workers: ${repoError.message}")
                _workers.update { DataState.Error(repoError) }
                repoError.left()
            },
            ifRight = { workerList ->
                _workers.update { DataState.Success(workerList) }
                logger.debug("Successfully loaded ${workerList.size} workers")
                Unit.right()
            }
        )
    }

    override suspend fun updateWorker(
        id: Long,
        displayName: String,
        allowedScopes: List<String>,
        toolNamePrefix: String?
    ): Either<RepositoryError, Unit> {
        return workerApi.updateWorker(id, displayName, allowedScopes, toolNamePrefix)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update worker")
            }
            .map { _ ->
                // Refresh the worker list to reflect the changes
                loadWorkers()
                // A prefix change renames the worker's built-in tool public names server-side.
                // Refresh the shared tool cache so the Configure Tools dialog shows the new names
                // immediately instead of the stale cached values.
                toolRepository.loadTools()
                // Refresh the per-worker built-in tool cache so the Built-in Tools tab's Edit Tool
                // dialog shows the renamed public names immediately instead of the stale values.
                builtInToolRepository.loadTools(id)
            }
    }

    override suspend fun deleteWorker(id: Long): Either<RepositoryError, Unit> {
        return workerApi.deleteWorker(id)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to delete worker")
            }
            .onRight {
                // Refresh the worker list to reflect the removal
                loadWorkers()
            }
    }
}
