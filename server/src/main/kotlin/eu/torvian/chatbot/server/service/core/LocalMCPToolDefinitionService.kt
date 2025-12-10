package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.service.core.error.mcp.*

/**
 * Service interface for managing Local MCP Tool Definitions.
 * Coordinates between ToolDefinitionDao and LocalMCPToolDefinitionDao to maintain consistency.
 * All operations are atomic and wrapped in database transactions.
 */
interface LocalMCPToolDefinitionService {
    /**
     * Creates multiple MCP tools in a single atomic transaction.
     * Each tool is linked to the specified MCP server.
     *
     * @param serverId The ID of the MCP server that provides these tools
     * @param tools List of LocalMCPToolDefinition objects to create
     * @return Either a [CreateMCPToolsError] if validation or creation fails,
     *         or a list of created [LocalMCPToolDefinition] objects with assigned IDs
     */
    suspend fun createMCPTools(
        serverId: Long,
        tools: List<LocalMCPToolDefinition>
    ): Either<CreateMCPToolsError, List<LocalMCPToolDefinition>>

    /**
     * Retrieves all MCP tools associated with a specific server.
     *
     * @param serverId The ID of the MCP server
     * @return Either a [GetMCPToolsByServerIdError] if the server doesn't exist,
     *         or a list of [LocalMCPToolDefinition] objects (empty list if no tools)
     */
    suspend fun getMCPToolsByServerId(
        serverId: Long
    ): Either<GetMCPToolsByServerIdError, List<LocalMCPToolDefinition>>

    /**
     * Retrieves all MCP tools for all servers owned by a specific user.
     *
     * @param userId The ID of the user
     * @return List of [LocalMCPToolDefinition] objects (empty list if no tools)
     */
    suspend fun getMCPToolsForUser(userId: Long): List<LocalMCPToolDefinition>

    /**
     * Retrieves a single MCP tool by its ID.
     * Validates that the tool is actually an MCP tool.
     *
     * @param toolId The ID of the tool to retrieve
     * @return Either a [GetMCPToolByIdError] if the tool doesn't exist or isn't an MCP tool,
     *         or the [LocalMCPToolDefinition]
     */
    suspend fun getMCPToolById(
        toolId: Long
    ): Either<GetMCPToolByIdError, LocalMCPToolDefinition>

    /**
     * Updates an existing MCP tool and its linkage.
     * Validates all fields before persisting changes.
     *
     * @param tool The updated LocalMCPToolDefinition
     * @return Either an [UpdateMCPToolError] if validation or update fails,
     *         or the updated [LocalMCPToolDefinition]
     */
    suspend fun updateMCPTool(
        tool: LocalMCPToolDefinition
    ): Either<UpdateMCPToolError, LocalMCPToolDefinition>

    /**
     * Deletes all MCP tools associated with a specific server.
     * Linkages are cascade-deleted automatically.
     *
     * @param serverId The ID of the MCP server
     * @return Either a [DeleteMCPToolsForServerError] if the server doesn't exist,
     *         or the count of deleted tools
     */
    suspend fun deleteMCPToolsForServer(
        serverId: Long
    ): Either<DeleteMCPToolsForServerError, Int>

    /**
     * Performs a differential refresh of MCP tools for a server.
     * Compares the current tools with existing tools and:
     * - Adds new tools (not in existing)
     * - Updates changed tools (different schema/description)
     * - Deletes removed tools (not in current)
     *
     * @param serverId The ID of the MCP server
     * @param currentTools The current list of tools from the MCP server (ids are ignored)
     * @return Either a [RefreshMCPToolsError] if the operation fails,
     *         or a [RefreshResult] containing counts of changes
     */
    suspend fun refreshMCPTools(
        serverId: Long,
        currentTools: List<LocalMCPToolDefinition>
    ): Either<RefreshMCPToolsError, RefreshResult>
}

/**
 * Result of a tool refresh operation.
 *
 * @property addedTools List of tools that were added
 * @property updatedTools List of tools that were updated
 * @property deletedTools List of tools that were deleted
 */
data class RefreshResult(
    val addedTools: List<LocalMCPToolDefinition>,
    val updatedTools: List<LocalMCPToolDefinition>,
    val deletedTools: List<LocalMCPToolDefinition>
)

