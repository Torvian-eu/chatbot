package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.server.data.tables.OperatorToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed ResultRow (base tool definition joined with the operator linkage) to an
 * [OperatorToolDefinition].
 *
 * The row must include both [ToolDefinitionTable] and [OperatorToolDefinitionTable] columns, i.e. it
 * must come from a query that joined the operator linkage table (INNER or LEFT).
 *
 * @receiver Result row that includes [ToolDefinitionTable] and [OperatorToolDefinitionTable] columns.
 * @return Mapped [OperatorToolDefinition].
 */
fun ResultRow.toOperatorToolDefinition(): OperatorToolDefinition = OperatorToolDefinition(
    id = this[ToolDefinitionTable.id].value,
    name = this[ToolDefinitionTable.name],
    description = this[ToolDefinitionTable.description],
    config = Json.parseToJsonElement(this[ToolDefinitionTable.configJson]).let { it as kotlinx.serialization.json.JsonObject },
    inputSchema = Json.parseToJsonElement(this[ToolDefinitionTable.inputSchemaJson]).let { it as kotlinx.serialization.json.JsonObject },
    outputSchema = this[ToolDefinitionTable.outputSchemaJson]?.let {
        Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject
    },
    isEnabled = this[ToolDefinitionTable.isEnabled],
    createdAt = Instant.fromEpochMilliseconds(this[ToolDefinitionTable.createdAt]),
    updatedAt = Instant.fromEpochMilliseconds(this[ToolDefinitionTable.updatedAt]),
    userId = this[OperatorToolDefinitionTable.userId].value
)
