package eu.torvian.chatbot.worker.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Runtime-level representation of one discovered MCP tool.
 *
 * @property name Raw MCP tool name returned by runtime discovery. This value is **untrusted**: the MCP protocol
 *   places no restriction on its characters, so it may contain characters (dots, spaces, slashes, Unicode, etc.)
 *   that are illegal in LLM tool names. The server sanitizes it into the LLM-safe public name while preserving it
 *   verbatim as [name] for dispatch to the MCP server.
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