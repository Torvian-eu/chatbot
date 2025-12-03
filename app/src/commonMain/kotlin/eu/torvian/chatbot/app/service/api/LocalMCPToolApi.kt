package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition

/**
 * Frontend API interface for interacting with Local MCP Tool endpoints.
 *
 * This interface defines the operations for managing MCP tool definitions that are specific
 * to local MCP servers. Implementations use the internal HTTP API. All methods are suspend
 * functions and return [Either<ApiResourceError, T>].
 *
 * **Note**: This API is separate from [ToolApi] and handles MCP-specific tool operations.
 * MCP tools have specific batch operations and server-based grouping.
 */
interface LocalMCPToolApi {
    /**
     * Creates multiple MCP tools for a specific server in a single batch operation.
     *
     * The server will create both ToolDefinition entries and LocalMCPToolDefinition entries
     * atomically in a single transaction, assigning server-generated IDs.
     *
     * Corresponds to `POST /api/v1/local-mcp-tools/batch`.
     *
     * @param serverId The unique identifier of the MCP server
     * @param tools The list of tools to create
     * @return [Either.Right] containing the list of created tools with their IDs on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun createMCPToolsForServer(
        serverId: Long,
        tools: List<LocalMCPToolDefinition>
    ): Either<ApiResourceError, List<LocalMCPToolDefinition>>

    /**
     * Performs a differential refresh of tools for a specific server.
     *
     * Compares the current tools from the MCP server with existing tools in the database,
     * then adds new tools, updates changed tools, and removes deleted tools.
     *
     * Corresponds to `POST /api/v1/local-mcp-tools/refresh`.
     *
     * @param serverId The unique identifier of the MCP server
     * @param currentTools The current list of tools from the MCP server
     * @return [Either.Right] containing [RefreshMCPToolsResponse] with counts of added/updated/deleted tools on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun refreshMCPToolsForServer(
        serverId: Long,
        currentTools: List<LocalMCPToolDefinition>
    ): Either<ApiResourceError, RefreshMCPToolsResponse>

    /**
     * Retrieves all MCP tools for a specific server.
     *
     * Corresponds to `GET /api/v1/local-mcp-tools/server/{serverId}`.
     *
     * @param serverId The unique identifier of the MCP server
     * @return [Either.Right] containing a list of [LocalMCPToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun getMCPToolsForServer(serverId: Long): Either<ApiResourceError, List<LocalMCPToolDefinition>>

    /**
     * Retrieves a single MCP tool by its ID.
     *
     * Corresponds to `GET /api/v1/local-mcp-tools/{toolId}`.
     *
     * @param toolId The unique identifier of the tool
     * @return [Either.Right] containing the [LocalMCPToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun getMCPToolById(toolId: Long): Either<ApiResourceError, LocalMCPToolDefinition>

    /**
     * Updates an existing MCP tool.
     *
     * Corresponds to `PUT /api/v1/local-mcp-tools/{toolId}`.
     *
     * @param tool The [LocalMCPToolDefinition] with updated details (id must be set)
     * @return [Either.Right] containing the updated [LocalMCPToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found, invalid input).
     */
    suspend fun updateMCPTool(tool: LocalMCPToolDefinition): Either<ApiResourceError, LocalMCPToolDefinition>

    /**
     * Deletes all MCP tools for a specific server.
     *
     * This operation removes all tools associated with the given server ID.
     * It's typically called when a server configuration is deleted or when refreshing all tools.
     *
     * Corresponds to `DELETE /api/v1/local-mcp-tools/server/{serverId}`.
     *
     * @param serverId The unique identifier of the MCP server whose tools should be deleted
     * @return [Either.Right] containing the count of deleted tools on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun deleteMCPToolsForServer(serverId: Long): Either<ApiResourceError, Int>
}

