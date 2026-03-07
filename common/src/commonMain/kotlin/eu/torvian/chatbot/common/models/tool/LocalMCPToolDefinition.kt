package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Represents a tool definition that is specific to a local MCP server.
 *
 * @property serverId Unique identifier for the MCP server that provides this tool
 * @property mcpToolName Original tool name from the MCP server (for name mapping)
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
