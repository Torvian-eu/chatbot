package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing Local MCP Tool definitions.
 *
 * This repository handles MCP-specific tool operations, separate from the general [ToolRepository].
 * It provides caching and state management for MCP tools organized by server ID.
 *
 * **Separation of Concerns**:
 * - ToolRepository = All tools
 * - LocalMCPToolRepository = Specific operations for MCP tools (uses ToolRepository internally)
 *
 */
interface LocalMCPToolRepository {
    /**
     * Flow of all MCP tools, organized by server ID. Derived from ToolRepository.tools.
     *
     * Map structure: serverId → List of LocalMCPToolDefinition
     * Enables efficient lookup by server without filtering.
     */
    val mcpTools: Flow<DataState<RepositoryError, Map<Long, List<LocalMCPToolDefinition>>>>

    /**
     * Loads all MCP tools for the current user from the server.
     *
     * Fetches all MCP tools from all servers owned by the authenticated user
     * and updates the cache.
     *
     * @return [Either.Right] with Unit on success, or [Either.Left] with [RepositoryError] on failure
     */
    suspend fun loadMCPTools(): Either<RepositoryError, Unit>

    /**
     * Persists discovered MCP tools for a specific server.
     *
     * Creates MCP tool definitions with linkages to the server in a single atomic operation.
     * Invalidates the cache and updates the StateFlow.
     *
     * @param serverId The ID of the MCP server
     * @param tools List of LocalMCPToolDefinition to create (IDs will be assigned by server)
     * @return [Either.Right] containing the created tools with server-generated IDs on success,
     *         or [Either.Left] with [RepositoryError] on failure
     */
    suspend fun persistMCPTools(
        serverId: Long,
        tools: List<LocalMCPToolDefinition>
    ): Either<RepositoryError, List<LocalMCPToolDefinition>>

    /**
     * Retrieves all tools for a specific MCP server.
     *
     * Returns cached tools if available, otherwise fetches from the server.
     *
     * @param serverId The ID of the MCP server
     * @return [Either.Right] containing the list of tools for the server on success,
     *         or [Either.Left] with [RepositoryError] on failure
     */
    suspend fun getToolsByServerId(serverId: Long): Either<RepositoryError, List<LocalMCPToolDefinition>>

    /**
     * Performs a differential refresh of tools for a specific MCP server.
     *
     * Compares the current tools from the MCP server with existing tools in the database,
     * then adds new tools, updates changed tools, and removes deleted tools.
     *
     * @param serverId The ID of the MCP server
     * @param currentTools The current list of tools from the MCP server
     * @return [Either.Right] containing refresh response with lists of added/updated/deleted tools on success,
     *         or [Either.Left] with [RepositoryError] on failure
     */
    suspend fun refreshMCPTools(
        serverId: Long,
        currentTools: List<LocalMCPToolDefinition>
    ): Either<RepositoryError, RefreshMCPToolsResponse>

    /**
     * Deletes all MCP tools for a specific server.
     *
     * Removes all LocalMCPToolDefinition entries for the server and invalidates the cache.
     *
     * @param serverId The ID of the MCP server whose tools should be deleted
     * @return [Either.Right] containing the count of deleted tools on success,
     *         or [Either.Left] with [RepositoryError] on failure
     */
    suspend fun deleteMCPToolsForServer(serverId: Long): Either<RepositoryError, Int>

    /**
     * Updates an existing MCP tool.
     *
     * @param tool The updated LocalMCPToolDefinition
     * @return [Either.Right] with Unit on success, or [Either.Left] with [RepositoryError] on failure
     */
    suspend fun updateMCPTool(tool: LocalMCPToolDefinition): Either<RepositoryError, Unit>

    /**
     * Batch updates multiple MCP tools.
     *
     * @param serverId The ID of the MCP server
     * @param toolDefinitions List of updated tool definitions
     * @return [Either.Right] with Unit on success, or [Either.Left] with [RepositoryError] on failure
     */
    suspend fun batchUpdateMCPTools(
        serverId: Long,
        toolDefinitions: List<LocalMCPToolDefinition>
    ): Either<RepositoryError, Unit>

    /**
     * Removes all tools for a specific MCP server from the cache.
     *
     * @param serverId The ID of the MCP server whose tools should be removed from the cache
     */
    suspend fun removeToolsFromCache(serverId: Long)

    /**
     * Invalidates the enabled tools cache for all sessions.
     */
    suspend fun invalidateEnabledToolsCache()
}
