package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.tool.MiscToolDefinition
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.ResultRow

/**
 * Maps an Exposed ResultRow from ToolDefinitionTable to a ToolDefinition DTO.
 *
 * Converts database representation (with timestamps as Long milliseconds and JSON as strings)
 * to domain model (with Instant timestamps and JsonObject).
 */
fun ResultRow.toToolDefinition() = MiscToolDefinition(
    id = this[ToolDefinitionTable.id].value,
    name = this[ToolDefinitionTable.name],
    description = this[ToolDefinitionTable.description],
    type = this[ToolDefinitionTable.type],
    config = Json.parseToJsonElement(this[ToolDefinitionTable.configJson]).let { it as JsonObject },
    inputSchema = Json.parseToJsonElement(this[ToolDefinitionTable.inputSchemaJson]).let { it as JsonObject },
    outputSchema = this[ToolDefinitionTable.outputSchemaJson]?.let {
        Json.parseToJsonElement(it) as JsonObject
    },
    isEnabled = this[ToolDefinitionTable.isEnabled],
    createdAt = Instant.fromEpochMilliseconds(this[ToolDefinitionTable.createdAt]),
    updatedAt = Instant.fromEpochMilliseconds(this[ToolDefinitionTable.updatedAt])
)
