package eu.torvian.chatbot.common.models.api.tool

import eu.torvian.chatbot.common.models.tool.ToolType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request body for creating a new tool definition.
 *
 * @property name The unique name of the tool (machine-readable, used in LLM API calls)
 * @property description A description of what the tool does
 * @property type The type of tool (e.g., WEB_SEARCH, CALCULATOR)
 * @property config Tool-specific configuration (JSON object)
 * @property inputSchema JSON Schema defining expected input parameters
 * @property outputSchema Optional JSON Schema defining expected output structure
 * @property isEnabled Whether the tool is enabled by default
 */
@Serializable
data class CreateToolRequest(
    val name: String,
    val description: String,
    val type: ToolType,
    val config: JsonObject,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val isEnabled: Boolean = true
)

