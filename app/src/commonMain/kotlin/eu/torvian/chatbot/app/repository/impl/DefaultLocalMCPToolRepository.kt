package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.LocalMCPToolRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.LocalMCPToolApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

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
 * @property toolRepository The repository for general tool operations
 */
class DefaultLocalMCPToolRepository(
    private val localMCPToolApi: LocalMCPToolApi,
    private val toolRepository: ToolRepository,
) : LocalMCPToolRepository {

    companion object {
        private val logger = kmpLogger<DefaultLocalMCPToolRepository>()
    }

    override val mcpTools: Flow<DataState<RepositoryError, Map<Long, List<LocalMCPToolDefinition>>>> =
        toolRepository.tools
            .map { dataState ->
                when (dataState) {
                    is DataState.Success -> {
                        val mcpTools = dataState.data.filterIsInstance<LocalMCPToolDefinition>()
                        DataState.Success(mcpTools.groupBy { it.serverId })
                    }

                    is DataState.Error -> dataState
                    is DataState.Loading -> dataState
                    is DataState.Idle -> dataState
                }
            }

    override suspend fun loadMCPTools(): Either<RepositoryError, Unit> {
        return toolRepository.loadTools()
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
        toolRepository.updateToolCache { currentList ->
            currentList + createdTools
        }

        logger.info("Persisted ${createdTools.size} MCP tools for server $serverId")
        createdTools
    }

    override suspend fun getToolsByServerId(serverId: Long): Either<RepositoryError, List<LocalMCPToolDefinition>> =
        either {
            // Try to get from cache first
            toolRepository.tools.value.dataOrNull?.filterIsInstance<LocalMCPToolDefinition>()
                ?.filter { it.serverId == serverId }?.let { cachedTools ->
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
            toolRepository.updateToolCache { currentList ->
                currentList + tools
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
        toolRepository.updateToolCache { currentList ->
            currentList.filter { it !in refreshResponse.deletedTools && it !in refreshResponse.updatedTools } + refreshResponse.addedTools + refreshResponse.updatedTools
        }
        // Disable deleted tools for all sessions in cache
        toolRepository.updateEnabledToolsCache(refreshResponse.deletedTools, false)

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
        removeToolsFromCache(serverId)

        logger.info("Deleted $count MCP tools for server $serverId")
        count
    }

    override suspend fun updateMCPTool(tool: LocalMCPToolDefinition): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            logger.error("Failed to update MCP tool ${tool.id}: ${apiResourceError.message}")
            apiResourceError.toRepositoryError("Failed to update MCP tool")
        }) {
            localMCPToolApi.updateMCPTool(tool).bind()
        }

        // Update the tool in the cache
        val oldTool = toolRepository.tools.value.dataOrNull?.find { it.id == tool.id }
        toolRepository.updateToolCache { currentList ->
            currentList.map { if (it.id == tool.id) tool else it }
        }

        // If the tool's enabled state changed, invalidate all enabled tools caches
        if (oldTool?.isEnabled != tool.isEnabled) {
            toolRepository.invalidateEnabledToolsCache()
        }
    }

    override suspend fun removeToolsFromCache(serverId: Long) {
        val deletedTools = toolRepository.tools.value.dataOrNull?.filterIsInstance<LocalMCPToolDefinition>()
            ?.filter { it.serverId == serverId } ?: emptyList()
        toolRepository.updateToolCache { currentList ->
            currentList.filter { it !in deletedTools }
        }
        // Disable deleted tools for all sessions in cache
        toolRepository.updateEnabledToolsCache(deletedTools, false)
    }

    override suspend fun invalidateEnabledToolsCache() {
        toolRepository.invalidateEnabledToolsCache()
    }
}
