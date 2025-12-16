package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.data.tables.ToolCallTable
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.ResultRow

/**
 * Maps an Exposed ResultRow from ToolCallTable to a ToolCall DTO.
 *
 * Converts database representation (with timestamps as Long milliseconds and JSON as strings)
 * to domain model (with Instant timestamps and JSON strings).
 *
 */
fun ResultRow.toToolCall() = ToolCall(
    id = this[ToolCallTable.id].value,
    messageId = this[ToolCallTable.messageId].value,
    toolDefinitionId = this[ToolCallTable.toolDefinitionId]?.value,
    toolName = this[ToolCallTable.toolName],
    toolCallId = this[ToolCallTable.toolCallId],
    input = this[ToolCallTable.inputJson],
    output = this[ToolCallTable.outputJson],
    status = this[ToolCallTable.status],
    errorMessage = this[ToolCallTable.errorMessage],
    denialReason = this[ToolCallTable.denialReason],
    executedAt = Instant.fromEpochMilliseconds(this[ToolCallTable.executedAt]),
    durationMs = this[ToolCallTable.durationMs]
)
