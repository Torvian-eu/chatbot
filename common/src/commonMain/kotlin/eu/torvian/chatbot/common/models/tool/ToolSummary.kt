package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.Serializable

/**
 * Slim, wire-safe summary of a tool definition used by `list_tools`.
 *
 * Deliberately excludes the potentially large config/schema payloads and timestamps: the LLM-facing
 * `list_tools` handler returns these summaries so a model can enumerate what it may call without
 * inflating the conversation context. [read_tool] returns the full [ToolDefinition] for one id.
 *
 * @property id Unique identifier for the tool definition.
 * @property name Machine-readable tool name (NOT globally unique).
 * @property description Human-readable explanation of the tool's purpose.
 * @property type Category of tool, determining which executor handles it.
 * @property isEnabled Whether the tool is available for the owning/accessing user.
 */
@Serializable
data class ToolSummary(
    val id: Long,
    val name: String,
    val description: String,
    val type: ToolType,
    val isEnabled: Boolean
)
