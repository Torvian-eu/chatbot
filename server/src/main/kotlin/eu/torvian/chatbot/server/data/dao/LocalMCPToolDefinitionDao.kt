package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.data.dao.error.InsertToolError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPToolDefinitionError

/**
 * Data Access Object for LocalMCPToolDefinition domain models.
 *
 * Manages the many-to-many relationship between MCP servers and tool definitions.
 * This DAO handles queries that join the tool definitions table with the junction table
 * to provide complete LocalMCPToolDefinition domain models.
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
     * @param isEnabledByDefault Whether this tool is enabled by default for NEW chat sessions
     *   (null = use server-level default, true = enable, false = disable)
     * @return Either [InsertToolError] or Unit on success
     */
    suspend fun insertTool(
        toolDefinitionId: Long,
        mcpServerId: Long,
        mcpToolName: String? = null,
        isEnabledByDefault: Boolean? = null
    ): Either<InsertToolError, Unit>

    /**
     * Retrieves a Local MCP tool definition by its tool definition ID.
     *
     * Performs a join between ToolDefinitionTable and LocalMCPToolDefinitionTable
     * to return the complete domain model.
     *
     * @param toolDefinitionId The ID of the tool definition
     * @return Either [LocalMCPToolDefinitionError.NotFound] if not found, or the [LocalMCPToolDefinition]
     */
    suspend fun getToolById(
        toolDefinitionId: Long
    ): Either<LocalMCPToolDefinitionError.NotFound, LocalMCPToolDefinition>

    /**
     * Retrieves all Local MCP tool definitions linked to a specific MCP server.
     *
     * Performs a join between ToolDefinitionTable and LocalMCPToolDefinitionTable
     * to return complete domain models for all tools provided by the specified server.
     *
     * @param mcpServerId The ID of the MCP server
     * @return List of [LocalMCPToolDefinition] (empty if none)
     */
    suspend fun getToolsByServerId(mcpServerId: Long): List<LocalMCPToolDefinition>

    /**
     * Retrieves all Local MCP tool definitions for a specific user.
     *
     * Performs joins between LocalMCPServerTable, ToolDefinitionTable, and LocalMCPToolDefinitionTable
     * to return all MCP tools that belong to servers owned by the user.
     *
     * @param userId The ID of the user
     * @return List of [LocalMCPToolDefinition] (empty if none)
     */
    suspend fun getToolsForUser(userId: Long): List<LocalMCPToolDefinition>

    /**
     * Updates the MCP-specific fields for a Local MCP tool.
     *
     * This allows updating mcpToolName and isEnabledByDefault without modifying
     * the core tool definition fields.
     *
     * @param toolDefinitionId The ID of the tool definition
     * @param mcpToolName Optional original tool name from MCP server (for future name mapping)
     * @param isEnabledByDefault Whether this tool is enabled by default for NEW chat sessions
     *   (null = use server-level default, true = enable, false = disable)
     * @return Either [LocalMCPToolDefinitionError.NotFound] or Unit on success
     */
    suspend fun updateTool(
        toolDefinitionId: Long,
        mcpToolName: String?,
        isEnabledByDefault: Boolean?
    ): Either<LocalMCPToolDefinitionError.NotFound, Unit>

    /**
     * Deletes all Local MCP tools linked to a specific MCP server.
     *
     * This is typically called before refreshing tools from a server, to remove
     * all existing linkages before creating new ones.
     *
     * @param mcpServerId The ID of the MCP server
     * @return Number of tools deleted
     */
    suspend fun deleteToolsByServerId(mcpServerId: Long): Int
}

