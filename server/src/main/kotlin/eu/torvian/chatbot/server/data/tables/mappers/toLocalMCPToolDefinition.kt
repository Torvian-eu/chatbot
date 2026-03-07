package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.data.tables.LocalMCPToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.ResultRow
import kotlin.time.Instant

/**
 * Maps a joined ResultRow from ToolDefinitionTable and LocalMCPToolDefinitionTable
 * to a LocalMCPToolDefinition domain model.
 *
 * This mapper expects the ResultRow to contain columns from both:
 * - ToolDefinitionTable (for base tool information)
 * - LocalMCPToolDefinitionTable (for MCP-specific fields)
 */
fun ResultRow.toLocalMCPToolDefinition() = LocalMCPToolDefinition(
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
    serverId = this[LocalMCPToolDefinitionTable.mcpServerId].value,
    mcpToolName = this[LocalMCPToolDefinitionTable.mcpToolName]
)

