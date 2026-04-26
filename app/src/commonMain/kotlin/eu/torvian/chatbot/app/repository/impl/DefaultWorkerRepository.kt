package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.WorkerRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
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
 */
class DefaultWorkerRepository(
    private val workerApi: WorkerApi
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
}
