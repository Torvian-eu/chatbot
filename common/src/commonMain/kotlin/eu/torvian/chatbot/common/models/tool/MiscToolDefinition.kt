package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Represents a tool definition for miscellaneous (Non-MCP) tools.
 *
 * @deprecated This type is retained for backwards compatibility but is no longer used by the
 *   tool execution pipeline. The server-side execution path that consumed [MiscToolDefinition] has
 *   been removed. New tool definitions should use [LocalMCPToolDefinition] or
 *   [BuiltInWorkerToolDefinition] instead.
 */
@Deprecated(
    message = "MiscToolDefinition is no longer dispatched by the orchestrator; use LocalMCPToolDefinition or BuiltInWorkerToolDefinition.",
    replaceWith = ReplaceWith("BuiltInWorkerToolDefinition"),
    level = DeprecationLevel.WARNING
)
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