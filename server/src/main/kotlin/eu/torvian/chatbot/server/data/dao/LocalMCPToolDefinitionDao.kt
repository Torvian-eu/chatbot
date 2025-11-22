package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.CreateLinkageError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPToolDefinitionError

/**
 * Data Access Object for LocalMCPToolDefinition linkages.
 *
 * Manages the many-to-many relationship between MCP servers and tool definitions.
 * This DAO handles the junction table that tracks which MCP server provides which tools.
 */
interface LocalMCPToolDefinitionDao {
    /**
     * Creates a linkage between a tool definition and an MCP server.
     *
     * This method is typically called when tools are discovered from an MCP server
     * and persisted to the database.
     *
     * @param toolDefinitionId The ID of the tool definition
     * @param mcpServerId The ID of the MCP server that provides this tool
     * @param mcpToolName Optional original tool name from MCP server (for future name mapping)
     * @return Either [CreateLinkageError] or Unit on success
     */
    suspend fun createLinkage(
        toolDefinitionId: Long,
        mcpServerId: Long,
        mcpToolName: String? = null
    ): Either<CreateLinkageError, Unit>

    /**
     * Retrieves all tool definition IDs linked to a specific MCP server.
     *
     * @param mcpServerId The ID of the MCP server
     * @return List of tool definition IDs (empty if none)
     */
    suspend fun getToolIdsByServerId(mcpServerId: Long): List<Long>

    /**
     * Retrieves the MCP server ID that provides a specific tool.
     *
     * @param toolDefinitionId The ID of the tool definition
     * @return Either [LocalMCPToolDefinitionError.NotFound] if not linked, or the server ID
     */
    suspend fun getServerIdByToolId(
        toolDefinitionId: Long
    ): Either<LocalMCPToolDefinitionError.NotFound, Long>

    /**
     * Deletes a linkage between a tool and an MCP server.
     *
     * Note: Due to CASCADE constraints, this is automatically handled when either
     * the tool or server is deleted. This method is provided for explicit unlinking
     * if needed (e.g., during tool refresh operations).
     *
     * @param toolDefinitionId The ID of the tool definition
     * @param mcpServerId The ID of the MCP server
     * @return Either [LocalMCPToolDefinitionError.NotFound] or Unit on success
     */
    suspend fun deleteLinkage(
        toolDefinitionId: Long,
        mcpServerId: Long
    ): Either<LocalMCPToolDefinitionError.NotFound, Unit>

    /**
     * Deletes all linkages for a specific MCP server.
     *
     * This is typically called before refreshing tools from a server, to remove
     * all existing linkages before creating new ones.
     *
     * @param mcpServerId The ID of the MCP server
     * @return Number of linkages deleted
     */
    suspend fun deleteAllLinkagesForServer(mcpServerId: Long): Int

    /**
     * Checks if a tool definition is linked to an MCP server.
     *
     * @param toolDefinitionId The ID of the tool definition
     * @param mcpServerId The ID of the MCP server
     * @return true if linked, false otherwise
     */
    suspend fun isLinked(toolDefinitionId: Long, mcpServerId: Long): Boolean

    /**
     * Retrieves the MCP tool name mapping for a specific tool.
     *
     * FUTURE FEATURE: Returns the original MCP server tool name if name mapping is configured.
     * Returns NotFound error if tool is not linked to any server.
     *
     * @param toolDefinitionId The ID of the tool definition
     * @return Either [LocalMCPToolDefinitionError.NotFound] if not linked, or the MCP tool name (null if no mapping)
     */
    suspend fun getMcpToolName(toolDefinitionId: Long): Either<LocalMCPToolDefinitionError.NotFound, String?>
}

