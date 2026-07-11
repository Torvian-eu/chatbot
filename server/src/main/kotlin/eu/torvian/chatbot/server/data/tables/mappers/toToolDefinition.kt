package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.tables.BuiltInToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.LocalMCPToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps a ResultRow to a ToolDefinition, handling all tool types polymorphically.
 *
 * This mapper handles both simple JOINs and LEFT JOINs with LocalMCPToolDefinitionTable and
 * BuiltInToolDefinitionTable:
 * - If type is MCP_LOCAL and MCP columns are present: returns LocalMCPToolDefinition
 * - If type is BUILTIN_WORKER and built-in columns are present: returns BuiltInWorkerToolDefinition
 * - Otherwise: throws, since no generic tool type is supported anymore.
 *
 * Safely handles NULL values from LEFT JOINs on non-MCP tools.
 */
fun ResultRow.toToolDefinition(): ToolDefinition {
    val toolType = this[ToolDefinitionTable.type]
    val id = this[ToolDefinitionTable.id].value
    val name = this[ToolDefinitionTable.name]
    val description = this[ToolDefinitionTable.description]
    val config = Json.parseToJsonElement(this[ToolDefinitionTable.configJson]).let { it as JsonObject }
    val inputSchema = Json.parseToJsonElement(this[ToolDefinitionTable.inputSchemaJson]).let { it as JsonObject }
    val outputSchema = this[ToolDefinitionTable.outputSchemaJson]?.let {
        Json.parseToJsonElement(it) as JsonObject
    }
    val isEnabled = this[ToolDefinitionTable.isEnabled]
    val createdAt = Instant.fromEpochMilliseconds(this[ToolDefinitionTable.createdAt])
    val updatedAt = Instant.fromEpochMilliseconds(this[ToolDefinitionTable.updatedAt])

    return when (toolType) {
        ToolType.MCP_LOCAL -> LocalMCPToolDefinition(
            id = id,
            name = name,
            description = description,
            config = config,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            isEnabled = isEnabled,
            createdAt = createdAt,
            updatedAt = updatedAt,
            serverId = this[LocalMCPToolDefinitionTable.mcpServerId].value,
            mcpToolName = this[LocalMCPToolDefinitionTable.mcpToolName]
        )

        ToolType.BUILTIN_WORKER -> BuiltInWorkerToolDefinition(
            id = id,
            name = name,
            description = description,
            config = config,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            isEnabled = isEnabled,
            createdAt = createdAt,
            updatedAt = updatedAt,
            workerId = this[BuiltInToolDefinitionTable.workerId].value,
            builtInToolName = this[BuiltInToolDefinitionTable.builtInToolName]
        )

        // MCP_REMOTE is declared but never persisted; no generic fallback type exists anymore.
        ToolType.MCP_REMOTE -> throw IllegalStateException("Unsupported tool type: $toolType")
    }
}
