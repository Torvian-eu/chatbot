package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.entities.ToolDefinitionEntity
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps a [ResultRow] from [ToolDefinitionTable] to a flat [ToolDefinitionEntity].
 *
 * Reads only the columns physically stored in the base table; it does **not** join the MCP or
 * built-in linkage tables, so it never fabricates type-specific fields. Use this for bare CRUD
 * operations that only touch `tool_definitions`.
 */
fun ResultRow.toToolDefinitionEntity(): ToolDefinitionEntity {
    val config = Json.parseToJsonElement(this[ToolDefinitionTable.configJson]).let { it as JsonObject }
    val inputSchema = Json.parseToJsonElement(this[ToolDefinitionTable.inputSchemaJson]).let { it as JsonObject }
    val outputSchema = this[ToolDefinitionTable.outputSchemaJson]?.let {
        Json.parseToJsonElement(it) as JsonObject
    }

    return ToolDefinitionEntity(
        id = this[ToolDefinitionTable.id].value,
        name = this[ToolDefinitionTable.name],
        description = this[ToolDefinitionTable.description],
        type = this[ToolDefinitionTable.type],
        config = config,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        isEnabled = this[ToolDefinitionTable.isEnabled],
        createdAt = Instant.fromEpochMilliseconds(this[ToolDefinitionTable.createdAt]),
        updatedAt = Instant.fromEpochMilliseconds(this[ToolDefinitionTable.updatedAt])
    )
}

