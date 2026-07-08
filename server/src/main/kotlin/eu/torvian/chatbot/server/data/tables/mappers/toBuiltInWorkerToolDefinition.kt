package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.server.data.tables.BuiltInToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed ResultRow (base tool definition joined with the built-in linkage) to a
 * [BuiltInWorkerToolDefinition].
 *
 * The public [BuiltInWorkerToolDefinition.name] is taken from [ToolDefinitionTable.name] (which may
 * include a configured prefix), while [BuiltInWorkerToolDefinition.builtInToolName] is the
 * unprefixed internal worker runtime name from [BuiltInToolDefinitionTable.builtInToolName].
 *
 * @receiver Result row that includes [ToolDefinitionTable] and [BuiltInToolDefinitionTable] columns.
 * @return Mapped [BuiltInWorkerToolDefinition].
 */
fun ResultRow.toBuiltInWorkerToolDefinition(): BuiltInWorkerToolDefinition = BuiltInWorkerToolDefinition(
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
    workerId = this[BuiltInToolDefinitionTable.workerId].value,
    builtInToolName = this[BuiltInToolDefinitionTable.builtInToolName]
)

