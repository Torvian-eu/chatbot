package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.tool.ToolType
import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * Exposed table definition for tool definitions.
 *
 * Stores the metadata, configuration, and schemas for available tools that can be used
 * by LLM assistants during conversations. Each tool represents a capability (like web search)
 * that the assistant can invoke to gather information or perform actions.
 *
 * NOTE: Tool names are NOT globally unique. Different users can have tools with the same name.
 * Uniqueness is only required within enabled tools for a given chat session, enforced at
 * the application level.
 *
 * @property name Identifier for the tool (e.g., "web_search", "calculator"). Not globally unique.
 * @property description Human-readable explanation of the tool's purpose and functionality
 * @property type The category of tool, determining which executor handles it
 * @property configJson JSON object containing tool-specific configuration (search engine URL, API keys, etc.)
 * @property inputSchemaJson JSON Schema (draft-07) defining the structure and validation rules for tool inputs
 * @property outputSchemaJson Optional JSON Schema defining the expected structure of tool outputs
 * @property isEnabled Global enable/disable flag; disabled tools cannot be used in any session
 * @property isEnabledByDefault Controls default enablement for NEW chat sessions (null = use server default)
 * @property createdAt Unix timestamp (milliseconds) when the tool definition was created
 * @property updatedAt Unix timestamp (milliseconds) when the tool definition was last modified
 */
object ToolDefinitionTable : LongIdTable("tool_definitions") {
    val name = varchar("name", 255)
    val description = text("description")
    val type = enumerationByName<ToolType>("type", 50)
    val configJson = text("config_json")
    val inputSchemaJson = text("input_schema_json")
    val outputSchemaJson = text("output_schema_json").nullable()
    val isEnabled = bool("is_enabled").default(true)
    val isEnabledByDefault = bool("is_enabled_by_default").nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
}
