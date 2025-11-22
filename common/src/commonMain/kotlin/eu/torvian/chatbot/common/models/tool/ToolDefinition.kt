package eu.torvian.chatbot.common.models.tool

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Represents a tool definition that can be used by LLM assistants.
 *
 * A tool definition includes all metadata needed to describe the tool to an LLM,
 * configure its behavior, and validate its inputs and outputs.
 *
 * @property id Unique identifier for this tool definition
 * @property name Machine-readable tool name (used in LLM API calls). NOT globally unique.
 * @property description Human-readable explanation of the tool's purpose
 * @property type Category of tool, determining which executor handles it
 * @property config Tool-specific configuration (JSON object)
 * @property inputSchema JSON Schema defining expected input parameters
 * @property outputSchema Optional JSON Schema defining expected output structure
 * @property isEnabled Whether this tool is globally available
 * @property isEnabledByDefault Whether this tool is enabled by default for NEW chat sessions
 *   (null = use server-level default, true = enable, false = disable)
 * @property createdAt Timestamp when the tool was created
 * @property updatedAt Timestamp when the tool was last modified
 */
@Serializable
data class ToolDefinition(
    val id: Long,
    val name: String,
    val description: String,
    val type: ToolType,
    val config: JsonObject,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val isEnabled: Boolean,
    val isEnabledByDefault: Boolean? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)

