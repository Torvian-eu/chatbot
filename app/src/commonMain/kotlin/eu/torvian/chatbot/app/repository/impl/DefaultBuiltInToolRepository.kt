package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.BuiltInToolRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.BuiltInToolApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [BuiltInToolRepository] providing built-in worker tool management
 * with reactive StateFlow updates.
 *
 * This implementation maintains an in-memory cache of built-in tools for the active worker
 * using StateFlow, automatically refreshing the cache after successful load and update
 * operations. It follows the same patterns as other repositories in the application for
 * consistency.
 *
 * To keep the Configure Tools dialog (which reads from the shared [ToolRepository]) in sync,
 * successful updates are also propagated into [ToolRepository.tools] and, when the enabled
 * state changes, the per-session enabled-tools cache is invalidated. This mirrors the
 * behavior already implemented by [eu.torvian.chatbot.app.repository.impl.DefaultLocalMCPToolRepository].
 *
 * @property builtInToolApi The API client for built-in worker tool operations.
 * @property toolRepository The shared tool repository whose cache backs the Configure Tools dialog.
 */
class DefaultBuiltInToolRepository(
    private val builtInToolApi: BuiltInToolApi,
    private val toolRepository: ToolRepository,
) : BuiltInToolRepository {

    companion object {
        private val logger = kmpLogger<DefaultBuiltInToolRepository>()
    }

    private val _builtInTools = MutableStateFlow<DataState<RepositoryError, List<BuiltInWorkerToolDefinition>>>(DataState.Idle)
    override val builtInTools: StateFlow<DataState<RepositoryError, List<BuiltInWorkerToolDefinition>>> =
        _builtInTools.asStateFlow()

    override suspend fun loadTools(workerId: Long): Either<RepositoryError, List<BuiltInWorkerToolDefinition>> {
        _builtInTools.update { DataState.Loading }

        return builtInToolApi.getBuiltInToolsForWorker(workerId).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load built-in tools for worker $workerId")
                logger.warn("Failed to load built-in tools for worker $workerId: ${repoError.message}")
                _builtInTools.update { DataState.Error(repoError) }
                repoError.left()
            },
            ifRight = { tools ->
                _builtInTools.update { DataState.Success(tools) }
                logger.debug("Successfully loaded ${tools.size} built-in tools for worker $workerId")
                tools.right()
            }
        )
    }

    override suspend fun updateBuiltInTool(
        tool: BuiltInWorkerToolDefinition
    ): Either<RepositoryError, BuiltInWorkerToolDefinition> {
        return builtInToolApi.updateBuiltInTool(tool).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to update built-in tool ${tool.id}")
                logger.warn("Failed to update built-in tool ${tool.id}: ${repoError.message}")
                repoError.left()
            },
            ifRight = { updatedTool ->
                // Update the cached list in place, preserving the active worker's other tools.
                _builtInTools.update { currentState ->
                    when (currentState) {
                        is DataState.Success -> {
                            val updatedList = currentState.data.map { existing ->
                                if (existing.id == updatedTool.id) updatedTool else existing
                            }
                            DataState.Success(updatedList)
                        }

                        else -> currentState
                    }
                }

                // Propagate the change to the shared ToolRepository so the Configure Tools dialog
                // (which reads toolRepository.tools and the per-session enabled cache) reflects the
                // change immediately, without requiring an app restart.
                val oldTool = toolRepository.tools.value.dataOrNull?.find { it.id == tool.id }
                toolRepository.updateToolCache { currentList ->
                    currentList.map { if (it.id == tool.id) tool else it }
                }
                // Only invalidate the enabled-tools cache when the enabled state actually changed,
                // avoiding unnecessary session reloads on pure metadata edits.
                if (oldTool?.isEnabled != tool.isEnabled) {
                    toolRepository.invalidateEnabledToolsCache()
                }

                logger.debug("Successfully updated built-in tool ${updatedTool.id}")
                updatedTool.right()
            }
        )
    }

    override suspend fun resetToDefaults(
        workerId: Long
    ): Either<RepositoryError, List<BuiltInWorkerToolDefinition>> {
        return builtInToolApi.resetBuiltInToolsToDefaults(workerId).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to reset built-in tools for worker $workerId")
                logger.warn("Failed to reset built-in tools for worker $workerId: ${repoError.message}")
                repoError.left()
            },
            ifRight = { tools ->
                // Refresh the cached list so the UI reflects the reconciled definitions immediately.
                _builtInTools.update { DataState.Success(tools) }

                // Keep the shared ToolRepository (which backs the Configure Tools dialog) in sync,
                // without forcing a network reload.
                toolRepository.updateToolCache { currentList ->
                    val byId = tools.associateBy { it.id }
                    currentList.map { existing -> byId[existing.id] ?: existing }
                }

                logger.debug("Successfully reset ${tools.size} built-in tools for worker $workerId")
                tools.right()
            }
        )
    }
}
