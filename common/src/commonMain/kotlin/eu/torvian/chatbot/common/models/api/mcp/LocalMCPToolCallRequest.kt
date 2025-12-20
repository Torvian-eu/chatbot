package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.Serializable

/**
 * Represents a tool call request from the LLM for a local MCP tool.
 *
 * @property toolCallId Unique identifier for the tool call
 * @property serverId ID of the MCP server to execute the tool on
 * @property toolName Name of the tool to execute
 * @property inputJson JSON input for the tool (may be null or invalid JSON)
 */
@Serializable
data class LocalMCPToolCallRequest(
    val toolCallId: Long,
    val serverId: Long,
    val toolName: String,
    val inputJson: String?
)