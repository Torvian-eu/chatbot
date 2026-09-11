package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.ToolApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [ToolRepository] that manages tool definitions and user tool
 * approval preferences.
 *
 * This repository maintains an internal cache of tool data using [MutableStateFlow] and
 * provides reactive updates to all observers. It delegates API operations to the injected
 * [ToolApi] and handles comprehensive error management through [RepositoryError].
 *
 * The repository ensures data consistency by automatically updating the internal StateFlow
 * whenever successful CRUD operations occur, eliminating the need for manual cache invalidation.
 *
 * A session's effective tools are resolved from its agent role on the backend; this repository
 * therefore keeps no per-session tool state.
 *
 * @property toolApi The API client for tool-related operations
 */
class DefaultToolRepository(
    private val toolApi: ToolApi
) : ToolRepository {

    companion object {
        private val logger = kmpLogger<DefaultToolRepository>()
    }

    private val _tools = MutableStateFlow<DataState<RepositoryError, List<ToolDefinition>>>(DataState.Idle)
    override val tools: StateFlow<DataState<RepositoryError, List<ToolDefinition>>> = _tools.asStateFlow()

    private val _toolApprovalPreferences =
        MutableStateFlow<DataState<RepositoryError, List<UserToolApprovalPreference>>>(DataState.Idle)
    override val toolApprovalPreferences: StateFlow<DataState<RepositoryError, List<UserToolApprovalPreference>>> =
        _toolApprovalPreferences.asStateFlow()

    override suspend fun loadTools(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_tools.value.isLoading) return Unit.right()

        _tools.update { DataState.Loading }

        return toolApi.getAllTools()
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load tools")
                _tools.update { DataState.Error(repositoryError) }
                repositoryError
            }
            .map { toolList ->
                _tools.update { DataState.Success(toolList) }
            }
    }

    override suspend fun getToolById(toolId: Long): Either<RepositoryError, ToolDefinition> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to get tool by ID")
        }) {
            toolApi.getToolById(toolId).bind()
        }
    }

    override suspend fun loadUserToolApprovalPreferences(): Either<RepositoryError, Unit> = either {
        // Prevent duplicate loading operations
        if (_toolApprovalPreferences.value.isLoading) return Unit.right()

        _toolApprovalPreferences.update { DataState.Loading }

        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to load user tool approval preferences")
        }) {
            toolApi.getAllToolApprovalPreferences().bind()
        }.also { preferences ->
            _toolApprovalPreferences.update { DataState.Success(preferences) }
        }
    }

    override suspend fun setToolApprovalPreference(
        toolDefinitionId: Long,
        autoApprove: Boolean,
        conditions: String?,
        denialReason: String?
    ): Either<RepositoryError, Unit> = either {
        val userToolApprovalPreference = withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to set tool approval preference")
        }) {
            toolApi.setToolApprovalPreference(
                toolDefinitionId = toolDefinitionId,
                autoApprove = autoApprove,
                conditions = conditions,
                denialReason = denialReason
            ).bind()
        }
        updateToolApprovalPreferencesCache { currentList ->
            currentList.filter { it.toolDefinitionId != toolDefinitionId } + userToolApprovalPreference
        }
    }

    override suspend fun deleteToolApprovalPreference(toolDefinitionId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to delete tool approval preference")
        }) {
            toolApi.deleteToolApprovalPreference(toolDefinitionId).bind()
        }
        updateToolApprovalPreferencesCache { currentList ->
            currentList.filter { it.toolDefinitionId != toolDefinitionId }
        }
    }

    override suspend fun updateToolCache(update: (List<ToolDefinition>) -> List<ToolDefinition>) {
        _tools.update { currentState ->
            when (currentState) {
                is DataState.Success -> {
                    DataState.Success(update(currentState.data))
                }

                else -> {
                    logger.warn("Skipping cache update because current state is not Success: $currentState")
                    currentState
                }
            }
        }
    }

    override suspend fun updateToolApprovalPreferencesCache(
        update: (List<UserToolApprovalPreference>) -> List<UserToolApprovalPreference>
    ) {
        _toolApprovalPreferences.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(update(currentState.data))
                else -> {
                    logger.warn("Skipping cache update because current state is not Success: $currentState")
                    currentState
                }
            }
        }

    }
}
