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
import eu.torvian.chatbot.common.models.api.tool.CreateToolRequest
import eu.torvian.chatbot.common.models.api.tool.SetToolEnabledRequest
import eu.torvian.chatbot.common.models.api.tool.SetToolsEnabledRequest
import eu.torvian.chatbot.common.models.tool.ToolDefinition
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

    override suspend fun loadTools(): Either<RepositoryError, Unit> = either {
        // Prevent duplicate loading operations
        if (_tools.value.isLoading) return Unit.right()

        _tools.update { DataState.Loading }

        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to load tools")
        }) {
            toolApi.getAllTools().bind()
        }.also { toolList ->
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

    override suspend fun createTool(request: CreateToolRequest): Either<RepositoryError, ToolDefinition> = either {
        val newTool = withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to create tool")
        }) {
            toolApi.createTool(request).bind()
        }

        // Add the new tool to the cache
        _tools.update { currentState ->
            when (currentState) {
                is DataState.Success -> {
                    val updatedTools = currentState.data + newTool
                    DataState.Success(updatedTools)
                }
                else -> currentState // Keep other states unchanged
            }
        }

        newTool
    }

    override suspend fun updateTool(tool: ToolDefinition): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to update tool")
        }) {
            toolApi.updateTool(tool).bind()
        }

        // Update the tool in the cache
        _tools.update { currentState ->
            when (currentState) {
                is DataState.Success -> {
                    val updatedTools = currentState.data.map { if (it.id == tool.id) tool else it }
                    DataState.Success(updatedTools)
                }
                else -> currentState // Keep other states unchanged
            }
        }
    }

    override suspend fun deleteTool(toolId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to delete tool")
        }) {
            toolApi.deleteTool(toolId).bind()
        }

        // Remove the tool from the cache
        _tools.update { currentState ->
            when (currentState) {
                is DataState.Success -> {
                    val updatedTools = currentState.data.filter { it.id != toolId }
                    DataState.Success(updatedTools)
                }
                else -> currentState // Keep other states unchanged
            }
        }
    }

    override suspend fun loadEnabledToolsForSession(sessionId: Long): Either<RepositoryError, List<ToolDefinition>> =
        either {
            val enabledToolsFlow = _enabledToolsFlowsMutex.withLock {
                _enabledToolsFlows.getOrPut(sessionId) {
                    MutableStateFlow(DataState.Idle)
                }
            }

            // Check if already loading
            if (enabledToolsFlow.value.isLoading) return emptyList<ToolDefinition>().right()

            enabledToolsFlow.update { DataState.Loading }

            withError({ apiResourceError ->
                apiResourceError.toRepositoryError("Failed to load enabled tools for session")
            }) {
                toolApi.getEnabledToolsForSession(sessionId).bind()
            }.also { toolList ->
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
        val request = SetToolEnabledRequest(enabled = enabled)

        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to set tool enabled for session")
        }) {
            toolApi.setToolEnabledForSession(sessionId, toolDefinition.id, request).bind()
        }

        // Update the enabled tools cache
        _enabledToolsFlowsMutex.withLock {
            _enabledToolsFlows[sessionId]?.update { currentState ->
                when (currentState) {
                    is DataState.Success -> {
                        val updatedTools = if (enabled) {
                            currentState.data + toolDefinition
                        } else {
                            currentState.data.filter { it.id != toolDefinition.id }
                        }
                        DataState.Success(updatedTools)
                    }
                    else -> currentState // Keep other states unchanged
                }
            }
        }
    }

    override suspend fun setToolsEnabledForSession(
        sessionId: Long,
        toolDefinitions: List<ToolDefinition>,
        enabled: Boolean
    ): Either<RepositoryError, Unit> = either {
        val request = SetToolsEnabledRequest(
            toolIds = toolDefinitions.map { it.id },
            enabled = enabled
        )

        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to set tools enabled for session")
        }) {
            toolApi.setToolsEnabledForSession(sessionId, request).bind()
        }

        // Update the enabled tools cache
        _enabledToolsFlowsMutex.withLock {
            _enabledToolsFlows[sessionId]?.update { currentState ->
                when (currentState) {
                    is DataState.Success -> {
                        val updatedTools = if (enabled) {
                            // Add all tools that are not already in the list
                            val existingIds = currentState.data.map { it.id }.toSet()
                            val newTools = toolDefinitions.filter { it.id !in existingIds }
                            currentState.data + newTools
                        } else {
                            // Remove all tools from the list
                            val toolIdsToRemove = toolDefinitions.map { it.id }.toSet()
                            currentState.data.filter { it.id !in toolIdsToRemove }
                        }
                        DataState.Success(updatedTools)
                    }
                    else -> currentState // Keep other states unchanged
                }
            }
        }
    }
}

