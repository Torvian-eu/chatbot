package eu.torvian.chatbot.common.models.api.mcp

import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import kotlinx.serialization.Serializable

/**
 * Request to create multiple MCP tools for a specific server.
 *
 * @property serverId The ID of the MCP server that provides these tools
 * @property tools List of tool definitions to create
 */
@Serializable
data class CreateMCPToolsRequest(
    val serverId: Long,
    val tools: List<LocalMCPToolDefinition>
)

/**
 * Response containing created MCP tools with server-generated IDs.
 *
 * @property tools List of created tools with their assigned IDs
 */
@Serializable
data class CreateMCPToolsResponse(
    val tools: List<LocalMCPToolDefinition>
)

/**
 * Response containing the count of deleted tools.
 *
 * @property count Number of tools that were deleted
 */
@Serializable
data class DeleteMCPToolsResponse(
    val count: Int
)

/**
 * Request to refresh MCP tools for a specific server.
 * Performs a differential update: adds new tools, updates changed tools, deletes removed tools.
 *
 * @property serverId The ID of the MCP server
 * @property currentTools The current list of tools from the MCP server (IDs are ignored)
 */
@Serializable
data class RefreshMCPToolsRequest(
    val serverId: Long,
    val currentTools: List<LocalMCPToolDefinition>
)

/**
 * Response containing the results of a tool refresh operation.
 *
 * @property added Number of tools that were added
 * @property updated Number of tools that were updated
 * @property deleted Number of tools that were deleted
 */
@Serializable
data class RefreshMCPToolsResponse(
    val added: Int,
    val updated: Int,
    val deleted: Int
)

