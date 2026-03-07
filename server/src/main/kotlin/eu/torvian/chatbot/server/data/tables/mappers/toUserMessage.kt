package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.server.data.tables.ChatMessageTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed ResultRow from ChatMessageTable table to a UserMessage DTO.
 */
fun ResultRow.toUserMessage(): ChatMessage.UserMessage {
    val id = this[ChatMessageTable.id].value
    val sessionId = this[ChatMessageTable.sessionId].value
    val content = this[ChatMessageTable.content]
    val createdAt = Instant.fromEpochMilliseconds(this[ChatMessageTable.createdAt])
    val updatedAt = Instant.fromEpochMilliseconds(this[ChatMessageTable.updatedAt])
    val parentMessageId = this[ChatMessageTable.parentMessageId]?.value
    val childrenMessageIdsString = this[ChatMessageTable.childrenMessageIds]
    val childrenMessageIds = Json.decodeFromString<List<Long>>(childrenMessageIdsString)
    val fileReferencesString = this[ChatMessageTable.fileReferences]
    val fileReferences = Json.decodeFromString<List<FileReference>>(fileReferencesString)

    return ChatMessage.UserMessage(
        id = id,
        sessionId = sessionId,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
        parentMessageId = parentMessageId,
        childrenMessageIds = childrenMessageIds,
        fileReferences = fileReferences
    )
}