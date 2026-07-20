package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Represents a tool definition that is specific to a local MCP server.
 *
 * @property serverId Unique identifier for the MCP server that provides this tool
 * @property name Public, LLM-facing tool name. This is the LLM-safe (sanitized) identifier derived from the raw
 *   MCP tool name plus the server's optional prefix; it is the value sent to the LLM provider and may only contain
 *   the characters `a-z A-Z 0-9 _ -`. It is never used for MCP dispatch.
 * @property mcpToolName Original tool name from the MCP server (for name mapping). This raw name is preserved
 *   verbatim and used by the worker to dispatch the call to the MCP server; it may contain characters that are
 *   illegal in LLM tool names. The public [name] is the LLM-safe (sanitized) identifier derived from it.
 */
@Serializable
data class LocalMCPToolDefinition(
    override val id: Long,
    override val name: String,
    override val description: String,
    override val config: JsonObject,
    override val inputSchema: JsonObject,
    override val outputSchema: JsonObject? = null,
    override val isEnabled: Boolean,
    override val createdAt: Instant,
    override val updatedAt: Instant,
    val serverId: Long,
    val mcpToolName: String,
) : ToolDefinition() {
    @SerialName("tool_type") // 'type' is a reserved property used by serialization
    override val type: ToolType = ToolType.MCP_LOCAL

    override fun withUpdatedAt(newUpdatedAt: Instant): ToolDefinition {
        return this.copy(updatedAt = newUpdatedAt)
    }
}
