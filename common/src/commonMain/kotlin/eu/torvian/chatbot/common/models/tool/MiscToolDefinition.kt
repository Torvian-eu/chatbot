package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Represents a tool definition for miscellaneous (Non-MCP) tools.
 */
@Serializable
data class MiscToolDefinition(
    override val id: Long,
    override val name: String,
    override val description: String,
    @SerialName("tool_type") // 'type' is a reserved property used by serialization
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