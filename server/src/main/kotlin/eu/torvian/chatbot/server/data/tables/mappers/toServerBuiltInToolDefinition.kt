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
fun ResultRow.toServerBuiltInToolDefinition(): ServerBuiltInToolDefinition {
    val id = this[ToolDefinitionTable.id].value
    // The canonical name is an application invariant (the seeder always writes it and V25
    // backfilled existing rows); a null here means data corruption or a partially applied
    // migration, so fail loudly instead of silently losing the dispatch key.
    val builtInToolName = this[ServerBuiltInToolDefinitionTable.builtInToolName]
        ?: throw IllegalStateException(
            "Server built-in tool definition $id has no built_in_tool_name"
        )
    return ServerBuiltInToolDefinition(
        id = id,
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
        userId = this[ServerBuiltInToolDefinitionTable.userId].value,
        builtInToolName = builtInToolName
    )
}
