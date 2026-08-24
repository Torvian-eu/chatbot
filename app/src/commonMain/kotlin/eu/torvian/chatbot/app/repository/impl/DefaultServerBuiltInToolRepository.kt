package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ServerBuiltInToolRepository
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.ServerBuiltInToolApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [ServerBuiltInToolRepository] providing per-user server built-in tool
 * management with reactive StateFlow updates.
 *
 * This implementation maintains an in-memory cache of the current user's server built-in tools
 * using StateFlow, automatically refreshing the cache after successful load and update operations.
 *
 * To keep the Configure Tools dialog (which reads from the shared [ToolRepository]) in sync,
 * successful updates are also propagated into [ToolRepository.tools] and, when the enabled state
 * changes, the per-session enabled-tools cache is invalidated. This mirrors the behavior already
 * implemented by [DefaultOperatorToolRepository].
 *
 * @property serverBuiltInToolApi The API client for server built-in tool operations.
 * @property toolRepository The shared tool repository whose cache backs the Configure Tools dialog.
 */
class DefaultServerBuiltInToolRepository(
    private val serverBuiltInToolApi: ServerBuiltInToolApi,
    private val toolRepository: ToolRepository,
) : ServerBuiltInToolRepository {

    companion object {
        private val logger = kmpLogger<DefaultServerBuiltInToolRepository>()
    }

    private val _serverBuiltInTools =
        MutableStateFlow<DataState<RepositoryError, List<ServerBuiltInToolDefinition>>>(DataState.Idle)

    override val serverBuiltInTools: StateFlow<DataState<RepositoryError, List<ServerBuiltInToolDefinition>>> =
        _serverBuiltInTools.asStateFlow()

    override suspend fun loadTools(): Either<RepositoryError, Unit> {
        _serverBuiltInTools.update { DataState.Loading }

        return serverBuiltInToolApi.getServerBuiltInTools().fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load server built-in tools")
                logger.warn("Failed to load server built-in tools: ${repoError.message}")
                _serverBuiltInTools.update { DataState.Error(repoError) }
                repoError.left()
            },
            ifRight = { tools ->
                _serverBuiltInTools.update { DataState.Success(tools) }
                logger.debug("Successfully loaded ${tools.size} server built-in tools")
                Unit.right()
            }
        )
    }

    override suspend fun updateServerBuiltInTool(
        tool: ServerBuiltInToolDefinition
    ): Either<RepositoryError, ServerBuiltInToolDefinition> {
        return serverBuiltInToolApi.updateServerBuiltInTool(tool).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to update server built-in tool ${tool.id}")
                logger.warn("Failed to update server built-in tool ${tool.id}: ${repoError.message}")
                repoError.left()
            },
            ifRight = { updatedTool ->
                // Update the cached list in place, preserving the user's other server built-in tools.
                _serverBuiltInTools.update { currentState ->
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
                // change immediately, without requiring an app restart. The server-returned
                // `updatedTool` is the authoritative row (fresh updatedAt, immutable name preserved),
                // so it — not the request object — is what the cache must store.
                val oldTool = toolRepository.tools.value.dataOrNull?.find { it.id == updatedTool.id }
                toolRepository.updateToolCache { currentList ->
                    currentList.map { if (it.id == updatedTool.id) updatedTool else it }
                }
                // Only invalidate the enabled-tools cache when the enabled state actually changed,
                // avoiding unnecessary session reloads on pure metadata edits.
                if (oldTool?.isEnabled != updatedTool.isEnabled) {
                    toolRepository.invalidateEnabledToolsCache()
                }

                logger.debug("Successfully updated server built-in tool ${updatedTool.id}")
                updatedTool.right()
            }
        )
    }

    override suspend fun resetToDefaults(): Either<RepositoryError, List<ServerBuiltInToolDefinition>> {
        return serverBuiltInToolApi.resetServerBuiltInToolsToDefaults().fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to reset server built-in tools")
                logger.warn("Failed to reset server built-in tools: ${repoError.message}")
                repoError.left()
            },
            ifRight = { tools ->
                // Refresh the cached list so the UI reflects the reconciled definitions immediately.
                _serverBuiltInTools.update { DataState.Success(tools) }

                // Keep the shared ToolRepository (which backs the Configure Tools dialog) in sync,
                // without forcing a network reload.
                toolRepository.updateToolCache { currentList ->
                    val byId = tools.associateBy { it.id }
                    currentList.map { existing -> byId[existing.id] ?: existing }
                }

                logger.debug("Successfully reset ${tools.size} server built-in tools")
                tools.right()
            }
        )
    }
}
