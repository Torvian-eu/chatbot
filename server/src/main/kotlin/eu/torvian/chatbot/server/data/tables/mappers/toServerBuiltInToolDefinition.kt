package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.server.data.tables.ServerBuiltInToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed ResultRow (base tool definition joined with the server built-in linkage) to a
 * [ServerBuiltInToolDefinition].
 *
 * The row must include both [ToolDefinitionTable] and [ServerBuiltInToolDefinitionTable] columns,
 * i.e. it must come from a query that joined the server built-in linkage table (INNER or LEFT).
 *
 * @receiver Result row that includes [ToolDefinitionTable] and [ServerBuiltInToolDefinitionTable]
 *            columns.
 * @return Mapped [ServerBuiltInToolDefinition].
 */
fun ResultRow.toServerBuiltInToolDefinition(): ServerBuiltInToolDefinition = ServerBuiltInToolDefinition(
    id = this[ToolDefinitionTable.id].value,
    name = this[ToolDefinitionTable.name],
    description = this[ToolDefinitionTable.description],
    config = Json.parseToJsonElement(this[ToolDefinitionTable.configJson]).let { it as JsonObject },
    inputSchema = Json.parseToJsonElement(this[ToolDefinitionTable.inputSchemaJson]).let { it as JsonObject },
    outputSchema = this[ToolDefinitionTable.outputSchemaJson]?.let {
        Json.parseToJsonElement(it) as JsonObject
    },
    isEnabled = this[ToolDefinitionTable.isEnabled],
    createdAt = Instant.fromEpochMilliseconds(this[ToolDefinitionTable.createdAt]),
    updatedAt = Instant.fromEpochMilliseconds(this[ToolDefinitionTable.updatedAt]),
    userId = this[ServerBuiltInToolDefinitionTable.userId].value
)
