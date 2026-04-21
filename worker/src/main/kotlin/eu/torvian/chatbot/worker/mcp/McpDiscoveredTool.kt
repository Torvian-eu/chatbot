package eu.torvian.chatbot.worker.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Runtime-level representation of one discovered MCP tool.
 *
 * @property name Raw MCP tool name returned by runtime discovery.
 * @property description Optional MCP tool description.
 * @property inputSchema MCP tool input JSON schema.
 * @property outputSchema Optional MCP tool output JSON schema.
 */
data class McpDiscoveredTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null
)