package eu.torvian.chatbot.server.data.entities

import eu.torvian.chatbot.common.models.tool.ToolType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Flat, table-shaped representation of a row in `tool_definitions`.
 *
 * Unlike the sealed [eu.torvian.chatbot.common.models.tool.ToolDefinition] hierarchy, this type
 * carries only the columns physically stored in [eu.torvian.chatbot.server.data.tables.ToolDefinitionTable]
 * and does **not** attempt to materialize the type-specific linkage data (MCP server id, worker id,
 * etc.) that live in separate junction tables. It is the honest return type for bare CRUD
 * operations that read or write the base table without joining the linkage tables.
 *
 * @property id Unique tool definition identifier.
 * @property name Machine-readable tool name used in LLM API calls. Not globally unique.
 * @property description Human-readable explanation of the tool's purpose.
 * @property type Category of tool, determining which executor handles it.
 * @property config Tool-specific configuration (JSON object).
 * @property inputSchema JSON Schema defining expected input parameters.
 * @property outputSchema Optional JSON Schema defining expected output structure.
 * @property isEnabled Whether this tool is globally available.
 * @property createdAt Creation timestamp.
 * @property updatedAt Last update timestamp.
 */
@Serializable
data class ToolDefinitionEntity(
    val id: Long,
    val name: String,
    val description: String,
    val type: ToolType,
    val config: JsonObject,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject?,
    val isEnabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

