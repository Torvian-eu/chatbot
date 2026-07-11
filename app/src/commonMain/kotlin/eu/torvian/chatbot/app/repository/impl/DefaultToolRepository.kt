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
import eu.torvian.chatbot.app.utils.misc.LruCache
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default implementation of [ToolRepository] that manages tool definitions and session-specific configurations.
 *
 * This repository maintains an internal cache of tool data using [MutableStateFlow] and
 * provides reactive updates to all observers. It delegates API operations to the injected
 * [ToolApi] and handles comprehensive error management through [RepositoryError].
 *
 * The repository ensures data consistency by automatically updating the internal StateFlow
 * whenever successful CRUD operations occur, eliminating the need for manual cache invalidation.
 *
 * Session-specific enabled tools are cached separately with LRU eviction to avoid unbounded memory growth.
 *
 * @property toolApi The API client for tool-related operations
 */
class DefaultToolRepository(
    private val toolApi: ToolApi
) : ToolRepository {

    companion object {
        private val logger = kmpLogger<DefaultToolRepository>()
        private const val SESSION_TOOLS_CACHE_SIZE = 10
    }

    private val _tools = MutableStateFlow<DataState<RepositoryError, List<ToolDefinition>>>(DataState.Idle)
    override val tools: StateFlow<DataState<RepositoryError, List<ToolDefinition>>> = _tools.asStateFlow()

    // Cache for session-specific enabled tools flows, guarded by a Mutex for thread safety
    private val _enabledToolsFlowsMutex = Mutex()
    private val _enabledToolsFlows =
        LruCache<Long, MutableStateFlow<DataState<RepositoryError, List<ToolDefinition>>>>(SESSION_TOOLS_CACHE_SIZE)

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

    override suspend fun loadEnabledToolsForSession(sessionId: Long): Either<RepositoryError, List<ToolDefinition>> {
        val enabledToolsFlow = _enabledToolsFlowsMutex.withLock {
            _enabledToolsFlows.getOrPut(sessionId) {
                MutableStateFlow(DataState.Idle)
            }
        }

        // Check if already loading
        if (enabledToolsFlow.value.isLoading) return emptyList<ToolDefinition>().right()

        enabledToolsFlow.update { DataState.Loading }

        return toolApi.getEnabledToolsForSession(sessionId)
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load enabled tools for session")
                enabledToolsFlow.update { DataState.Error(repositoryError) }
                repositoryError
            }
            .onRight { toolList ->
                enabledToolsFlow.update { DataState.Success(toolList) }
            }
    }

    override suspend fun getEnabledToolsForSessionFlow(
        sessionId: Long
    ): StateFlow<DataState<RepositoryError, List<ToolDefinition>>> {
        return _enabledToolsFlowsMutex.withLock {
            _enabledToolsFlows.getOrPut(sessionId) {
                // If not found, create a new flow initialized to Idle
                MutableStateFlow(DataState.Idle)
            }.asStateFlow()
        }
    }

    override suspend fun setToolEnabledForSession(
        sessionId: Long,
        toolDefinition: ToolDefinition,
        enabled: Boolean
    ): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to set tool enabled for session")
        }) {
            toolApi.setToolEnabledForSession(sessionId, toolDefinition.id, enabled).bind()
        }

        // Update the enabled tools cache
        updateEnabledToolsCache(sessionId, toolDefinition, enabled)
    }

    override suspend fun setToolsEnabledForSession(
        sessionId: Long,
        toolDefinitions: List<ToolDefinition>,
        enabled: Boolean
    ): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to set tools enabled for session")
        }) {
            toolApi.setToolsEnabledForSession(sessionId, toolDefinitions.map { it.id }, enabled).bind()
        }

        // Update the enabled tools cache
        updateEnabledToolsCache(sessionId, toolDefinitions, enabled)
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

    /**
     * Applies a single tool enabled/disabled update to the in-memory cache.
     *
     * @param sessionId The ID of the session
     * @param tool The tool definition to enable/disable
     * @param enabled Whether to enable or disable the tool
     */
    private suspend fun updateEnabledToolsCache(sessionId: Long, tool: ToolDefinition, enabled: Boolean) {
        _enabledToolsFlowsMutex.withLock {
            _enabledToolsFlows[sessionId]?.update { currentState ->
                when (currentState) {
                    is DataState.Success -> {
                        val updatedTools = if (enabled) {
                            currentState.data + tool
                        } else {
                            currentState.data.filter { it.id != tool.id }
                        }
                        DataState.Success(updatedTools)
                    }

                    else -> {
                        logger.warn("Skipping cache update because current state is not Success: $currentState")
                        currentState
                    }
                }
            } ?: run {
                logger.warn("Tried to update enabled tools for session $sessionId but flow is not in the cache")
            }
        }
    }

    /**
     * Applies a single tool enabled/disabled update to the in-memory cache for all sessions.
     *
     * @param tool The tool definition to enable/disable
     * @param enabled Whether to enable or disable the tool
     */
    private suspend fun updateEnabledToolsCache(tool: ToolDefinition, enabled: Boolean) {
        val sessionIds = _enabledToolsFlowsMutex.withLock {
            _enabledToolsFlows.keys.toList()
        }
        sessionIds.forEach { sessionId ->
            updateEnabledToolsCache(sessionId, tool, enabled)
        }
    }

    override suspend fun updateEnabledToolsCache(sessionId: Long, tools: List<ToolDefinition>, enabled: Boolean) {
        _enabledToolsFlowsMutex.withLock {
            _enabledToolsFlows[sessionId]?.update { currentState ->
                when (currentState) {
                    is DataState.Success -> {
                        val updatedTools = if (enabled) {
                            // Add all tools that are not already in the list
                            val existingIds = currentState.data.map { it.id }.toSet()
                            val newTools = tools.filter { it.id !in existingIds }
                            currentState.data + newTools
                        } else {
                            // Remove all tools from the list
                            val toolIdsToRemove = tools.map { it.id }.toSet()
                            currentState.data.filter { it.id !in toolIdsToRemove }
                        }
                        DataState.Success(updatedTools)
                    }

                    else -> {
                        logger.warn("Skipping cache update because current state is not Success: $currentState")
                        currentState
                    }
                }
            } ?: run {
                logger.warn("Tried to update enabled tools for session $sessionId but flow is not in the cache")
            }
        }
    }

    override suspend fun updateEnabledToolsCache(tools: List<ToolDefinition>, enabled: Boolean) {
        val sessionIds = _enabledToolsFlowsMutex.withLock {
            _enabledToolsFlows.keys.toList()
        }
        sessionIds.forEach { sessionId ->
            updateEnabledToolsCache(sessionId, tools, enabled)
        }
    }

    override suspend fun invalidateEnabledToolsCache() {
        _enabledToolsFlowsMutex.withLock {
            _enabledToolsFlows.forEach { _, flow ->
                flow.update { DataState.Idle }
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
