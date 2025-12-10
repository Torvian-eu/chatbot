package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.LocalMCPToolRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.LocalMCPToolApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [LocalMCPToolRepository] that manages MCP tool definitions.
 *
 * This repository maintains an internal cache of MCP tool data organized by server ID
 * using [MutableStateFlow] and provides reactive updates to all observers. It delegates
 * API operations to the injected [LocalMCPToolApi] and handles comprehensive error
 * management through [RepositoryError].
 *
 * The repository ensures data consistency by automatically updating the internal StateFlow
 * whenever successful CRUD operations occur, eliminating the need for manual cache invalidation.
 *
 * **Separation of Concerns**:
 * - This repository handles MCP tools only
 * - ToolRepository handles non-MCP tools separately
 * - No dependency between the two repositories
 *
 * @property localMCPToolApi The API client for MCP tool operations
 */
class DefaultLocalMCPToolRepository(
    private val localMCPToolApi: LocalMCPToolApi
) : LocalMCPToolRepository {

    companion object {
        private val logger = kmpLogger<DefaultLocalMCPToolRepository>()
    }

    private val _mcpTools = MutableStateFlow<DataState<RepositoryError, Map<Long, List<LocalMCPToolDefinition>>>>(DataState.Idle)
    override val mcpTools: StateFlow<DataState<RepositoryError, Map<Long, List<LocalMCPToolDefinition>>>> = _mcpTools.asStateFlow()

    override suspend fun loadMCPTools(): Either<RepositoryError, Unit> = either {
        // Prevent duplicate loading operations
        if (_mcpTools.value.isLoading) return Unit.right()

        _mcpTools.update { DataState.Loading }

        val tools = withError({ apiResourceError ->
            logger.error("Failed to load MCP tools: ${apiResourceError.message}")
            apiResourceError.toRepositoryError("Failed to load MCP tools")
        }) {
            localMCPToolApi.getAllMCPTools().bind()
        }

        // Organize tools by server ID
        val toolsByServer = tools.groupBy { it.serverId }

        _mcpTools.update { DataState.Success(toolsByServer) }
        logger.info("Loaded ${tools.size} MCP tools from ${toolsByServer.size} servers")
    }

    override suspend fun persistMCPTools(
        serverId: Long,
        tools: List<LocalMCPToolDefinition>
    ): Either<RepositoryError, List<LocalMCPToolDefinition>> = either {
        val createdTools = withError({ apiResourceError ->
            logger.error("Failed to persist MCP tools for server $serverId: ${apiResourceError.message}")
            apiResourceError.toRepositoryError("Failed to persist MCP tools")
        }) {
            localMCPToolApi.createMCPToolsForServer(serverId, tools).bind()
        }

        // Update the cache with the new tools
        updateCache { current ->
            val updated = current.toMutableMap()
            val existingTools = updated[serverId] ?: emptyList()
            updated[serverId] = existingTools + createdTools
            updated
        }

        logger.info("Persisted ${createdTools.size} MCP tools for server $serverId")
        createdTools
    }

    override suspend fun getToolsByServerId(serverId: Long): Either<RepositoryError, List<LocalMCPToolDefinition>> = either {
        // Try to get from cache first
        _mcpTools.value.dataOrNull?.get(serverId)?.let { cachedTools ->
            logger.debug("Returning cached MCP tools for server $serverId: ${cachedTools.size} tools")
            return@either cachedTools
        }

        // Cache miss or not loaded yet - fetch from server
        val tools = withError({ apiResourceError ->
            logger.error("Failed to get MCP tools for server $serverId: ${apiResourceError.message}")
            apiResourceError.toRepositoryError("Failed to get MCP tools for server")
        }) {
            localMCPToolApi.getMCPToolsForServer(serverId).bind()
        }

        // Update cache with fetched tools
        updateCache { current ->
            val updated = current.toMutableMap()
            updated[serverId] = tools
            updated
        }

        logger.info("Fetched ${tools.size} MCP tools for server $serverId")
        tools
    }

    override suspend fun refreshMCPTools(
        serverId: Long,
        currentTools: List<LocalMCPToolDefinition>
    ): Either<RepositoryError, RefreshMCPToolsResponse> = either {
        val refreshResponse = withError({ apiResourceError ->
            logger.error("Failed to refresh MCP tools for server $serverId: ${apiResourceError.message}")
            apiResourceError.toRepositoryError("Failed to refresh MCP tools")
        }) {
            localMCPToolApi.refreshMCPToolsForServer(serverId, currentTools).bind()
        }

        // Update cache with the new state of tools after refresh
        updateCache { current ->
            val updated = current.toMutableMap()
            val existingTools = updated[serverId] ?: emptyList()

            // Create a map of existing tools by ID for quick lookup
            val existingToolsById = existingTools.associateBy { it.id }.toMutableMap()

            // Remove deleted tools from the cache
            refreshResponse.deletedTools.forEach { deletedTool ->
                existingToolsById.remove(deletedTool.id)
            }

            // Update existing tools with updated versions
            refreshResponse.updatedTools.forEach { updatedTool ->
                existingToolsById[updatedTool.id] = updatedTool
            }

            // Add new tools to the cache
            refreshResponse.addedTools.forEach { addedTool ->
                existingToolsById[addedTool.id] = addedTool
            }

            // Store the updated list of tools for this server
            updated[serverId] = existingToolsById.values.toList()
            updated
        }

        logger.info("Refreshed MCP tools for server $serverId: +${refreshResponse.addedTools.size} ~${refreshResponse.updatedTools.size} -${refreshResponse.deletedTools.size}")
        refreshResponse
    }

    override suspend fun deleteMCPToolsForServer(serverId: Long): Either<RepositoryError, Int> = either {
        val count = withError({ apiResourceError ->
            logger.error("Failed to delete MCP tools for server $serverId: ${apiResourceError.message}")
            apiResourceError.toRepositoryError("Failed to delete MCP tools")
        }) {
            localMCPToolApi.deleteMCPToolsForServer(serverId).bind()
        }

        // Remove tools for this server from cache
        updateCache { current ->
            val updated = current.toMutableMap()
            updated.remove(serverId)
            updated
        }

        logger.info("Deleted $count MCP tools for server $serverId")
        count
    }

    /**
     * Update the internal `_mcpTools` cache using [transform] when the current cache is in
     * [DataState.Success]. If the cache is in any other state the update is skipped and a
     * warning is logged.
     *
     * @param transform Pure function that receives the current map of [LocalMCPToolDefinition]
     * by server ID and returns the new map to store in the cache.
     */
    private fun updateCache(transform: (Map<Long, List<LocalMCPToolDefinition>>) -> Map<Long, List<LocalMCPToolDefinition>>) {
        _mcpTools.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                else -> {
                    logger.warn("Skipping cache update because current state is not Success: $currentState")
                    currentState
                }
            }
        }
    }
}
