package eu.torvian.chatbot.common.models.tool

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Represents a tool definition for miscellaneous (Non-MCP) tools.
 */
@Serializable
data class MiscToolDefinition(
    override val id: Long,
    override val name: String,
    override val description: String,
    override val type: ToolType,
    override val config: JsonObject,
    override val inputSchema: JsonObject,
    override val outputSchema: JsonObject? = null,
    override val isEnabled: Boolean,
    override val createdAt: Instant,
    override val updatedAt: Instant
) : ToolDefinition() {
    override fun withUpdatedAt(newUpdatedAt: Instant): ToolDefinition {
        return this.copy(updatedAt = newUpdatedAt)
    }
}