package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

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
 * @property createdAt Timestamp when the tool was created
 * @property updatedAt Timestamp when the tool was last modified
 */
@Serializable
sealed class ToolDefinition {
    abstract val id: Long
    abstract val name: String
    abstract val description: String
    abstract val type: ToolType
    abstract val config: JsonObject
    abstract val inputSchema: JsonObject
    abstract val outputSchema: JsonObject?
    abstract val isEnabled: Boolean
    abstract val createdAt: Instant
    abstract val updatedAt: Instant

    /**
     * Creates a copy of this tool definition with a new `updatedAt` timestamp.
     *
     * @param newUpdatedAt The new timestamp.
     * @return A new [ToolDefinition] instance with the updated timestamp.
     */
    abstract fun withUpdatedAt(newUpdatedAt: Instant): ToolDefinition
}
