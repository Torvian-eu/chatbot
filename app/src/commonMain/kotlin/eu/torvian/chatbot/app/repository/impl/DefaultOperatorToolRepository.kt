package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.OperatorToolRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.OperatorToolApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [OperatorToolRepository] providing per-user operator tool management
 * with reactive StateFlow updates.
 *
 * This implementation maintains an in-memory cache of the current user's operator tools using
 * StateFlow, automatically refreshing the cache after successful load and update operations.
 *
 * To keep the Configure Tools dialog (which reads from the shared [ToolRepository]) in sync,
 * successful updates are also propagated into [ToolRepository.tools] and, when the enabled
 * state changes, the per-session enabled-tools cache is invalidated. This mirrors the behavior
 * already implemented by [DefaultBuiltInToolRepository].
 *
 * @property operatorToolApi The API client for operator tool operations.
 * @property toolRepository The shared tool repository whose cache backs the Configure Tools dialog.
 */
class DefaultOperatorToolRepository(
    private val operatorToolApi: OperatorToolApi,
    private val toolRepository: ToolRepository,
) : OperatorToolRepository {

    companion object {
        private val logger = kmpLogger<DefaultOperatorToolRepository>()
    }

    private val _operatorTools =
        MutableStateFlow<DataState<RepositoryError, List<OperatorToolDefinition>>>(DataState.Idle)

    override val operatorTools: StateFlow<DataState<RepositoryError, List<OperatorToolDefinition>>> =
        _operatorTools.asStateFlow()

    override suspend fun loadTools(): Either<RepositoryError, Unit> {
        _operatorTools.update { DataState.Loading }

        return operatorToolApi.getOperatorTools().fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load operator tools")
                logger.warn("Failed to load operator tools: ${repoError.message}")
                _operatorTools.update { DataState.Error(repoError) }
                repoError.left()
            },
            ifRight = { tools ->
                _operatorTools.update { DataState.Success(tools) }
                logger.debug("Successfully loaded ${tools.size} operator tools")
                Unit.right()
            }
        )
    }

    override suspend fun updateOperatorTool(
        tool: OperatorToolDefinition
    ): Either<RepositoryError, OperatorToolDefinition> {
        return operatorToolApi.updateOperatorTool(tool).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to update operator tool ${tool.id}")
                logger.warn("Failed to update operator tool ${tool.id}: ${repoError.message}")
                repoError.left()
            },
            ifRight = { updatedTool ->
                // Update the cached list in place, preserving the user's other operator tools.
                _operatorTools.update { currentState ->
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

                logger.debug("Successfully updated operator tool ${updatedTool.id}")
                updatedTool.right()
            }
        )
    }

    override suspend fun resetToDefaults(): Either<RepositoryError, List<OperatorToolDefinition>> {
        return operatorToolApi.resetOperatorToolsToDefaults().fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to reset operator tools")
                logger.warn("Failed to reset operator tools: ${repoError.message}")
                repoError.left()
            },
            ifRight = { tools ->
                // Refresh the cached list so the UI reflects the reconciled definitions immediately.
                _operatorTools.update { DataState.Success(tools) }

                // Keep the shared ToolRepository (which backs the Configure Tools dialog) in sync,
                // without forcing a network reload.
                toolRepository.updateToolCache { currentList ->
                    val byId = tools.associateBy { it.id }
                    currentList.map { existing -> byId[existing.id] ?: existing }
                }

                logger.debug("Successfully reset ${tools.size} operator tools")
                tools.right()
            }
        )
    }
}
