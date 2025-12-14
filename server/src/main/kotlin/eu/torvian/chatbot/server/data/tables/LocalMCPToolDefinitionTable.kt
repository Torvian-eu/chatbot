package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Junction table linking MCP tools to their source MCP servers.
 *
 * This table tracks which LocalMCPServer provides which ToolDefinitions, enabling:
 * - Tool source tracking (which server provides which tools)
 * - Cascade deletion when MCP servers are removed
 * - Tool name mapping (mcpToolName field)
 *
 * When an MCP server is deleted, all associated tool definitions should also be deleted
 * (CASCADE on mcpServerId). When a tool definition is deleted independently, the linkage
 * is removed but the MCP server remains (CASCADE on toolDefinitionId).
 *
 * @property toolDefinitionId Reference to the tool definition in ToolDefinitionTable
 * @property mcpServerId Reference to the MCP server in LocalMCPServerTable
 * @property mcpToolName Original tool name from MCP server for name mapping.
 *   Maps LLM tool name to MCP tool name.
 * @property isEnabledByDefault Whether this tool is enabled by default for NEW chat sessions.
 *   (null = use server-level default, true = enable, false = disable)
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
    val mcpToolName = varchar("mcp_tool_name", 255)
    val isEnabledByDefault = bool("is_enabled_by_default").nullable()

    override val primaryKey = PrimaryKey(toolDefinitionId)
}

