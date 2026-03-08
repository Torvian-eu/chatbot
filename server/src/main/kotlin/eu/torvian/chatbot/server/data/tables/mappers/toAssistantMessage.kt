package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.server.data.tables.AssistantMessageTable
import eu.torvian.chatbot.server.data.tables.ChatMessageTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed ResultRow from a joined ChatMessageTable and AssistantMessageTable query to an AssistantMessage DTO.
 * This works with the results of a LEFT JOIN query.
 */
fun ResultRow.toAssistantMessage(): ChatMessage.AssistantMessage {
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

    // Get model and settings IDs from the joined result
    val modelId = this.getOrNull(AssistantMessageTable.modelId)?.value
    val settingsId = this.getOrNull(AssistantMessageTable.settingsId)?.value

    return ChatMessage.AssistantMessage(
        id = id,
        sessionId = sessionId,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
        parentMessageId = parentMessageId,
        childrenMessageIds = childrenMessageIds,
        fileReferences = fileReferences,
        modelId = modelId,
        settingsId = settingsId
    )
}