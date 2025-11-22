package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Junction table linking MCP tools to their source MCP servers.
 *
 * This table tracks which LocalMCPServer provides which ToolDefinitions, enabling:
 * - Tool source tracking (which server provides which tools)
 * - Cascade deletion when MCP servers are removed
 * - Future feature: Tool name mapping (mcpToolName field)
 *
 * When an MCP server is deleted, all associated tool definitions should also be deleted
 * (CASCADE on mcpServerId). When a tool definition is deleted independently, the linkage
 * is removed but the MCP server remains (CASCADE on toolDefinitionId).
 *
 * @property toolDefinitionId Reference to the tool definition in ToolDefinitionTable
 * @property mcpServerId Reference to the MCP server in LocalMCPServerTable
 * @property mcpToolName FUTURE: Original tool name from MCP server for name mapping.
 *   If null, use ToolDefinition.name as-is. If set, maps LLM tool name to MCP tool name.
 */
object LocalMCPToolDefinitionTable : Table("local_mcp_tool_definitions") {
    val toolDefinitionId = reference(
        "tool_definition_id",
        ToolDefinitionTable,
        onDelete = ReferenceOption.CASCADE
    )
    val mcpServerId = reference(
        "mcp_server_id",
        LocalMCPServerTable,
        onDelete = ReferenceOption.CASCADE
    )
    val mcpToolName = varchar("mcp_tool_name", 255).nullable()

    // Composite primary key: one tool can only be linked to one server
    override val primaryKey = PrimaryKey(toolDefinitionId, mcpServerId)
}

