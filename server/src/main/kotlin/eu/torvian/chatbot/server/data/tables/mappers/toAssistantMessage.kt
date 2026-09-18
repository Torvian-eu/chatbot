package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.server.data.tables.AssistantMessageTable
import eu.torvian.chatbot.server.data.tables.ChatMessageTable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Logger for read-side recovery from foreign/corrupt completion values.
 *
 * Kept as a file-level logger because the completion columns are read by a top-level mapper function.
 */
private val logger: Logger = LogManager.getLogger("toAssistantMessageMapper")

/**
 * Maps an Exposed ResultRow from a joined ChatMessageTable and AssistantMessageTable query to an AssistantMessage DTO.
 * This works with the results of a LEFT JOIN query.
 *
 * The completion columns (`is_complete`, `incomplete_cause`, `error_code`, `error_message`) are read defensively:
 * the cause and code are stored as enum *names* and an unrecognized value degrades to `null` (rendering nothing)
 * with a warning, instead of throwing and failing the read of an entire session.
 *
 * @receiver A row of a `chat_messages` LEFT JOIN `assistant_messages` query.
 * @return The mapped assistant message, including its persisted completion state.
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

    // Get model, settings and agent role IDs from the joined result
    val modelId = this.getOrNull(AssistantMessageTable.modelId)?.value
    val settingsId = this.getOrNull(AssistantMessageTable.settingsId)?.value
    val agentRoleId = this.getOrNull(AssistantMessageTable.agentRoleId)?.value

    // Deserialize the stored reasoning JSON array into raw items; unparseable/absent storage yields null.
    val reasoningItems = this.getOrNull(AssistantMessageTable.reasoningItemsJson)
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching {
            Json.decodeFromString(ListSerializer(JsonObject.serializer()), it)
        }.getOrNull() }

    // Completion state: the flag is NOT NULL in the schema, so a missing value is treated as completed (the
    // documented default for rows and payloads that predate the column).
    val isComplete = this.getOrNull(AssistantMessageTable.isComplete) ?: true
    val incompleteCause = this.getOrNull(AssistantMessageTable.incompleteCause)
        .toEnumByNameOrNull(AssistantMessageIncompleteCause.entries, id, AssistantMessageTable.incompleteCause.name)
    val errorCode = this.getOrNull(AssistantMessageTable.errorCode)
        .toEnumByNameOrNull(AssistantMessageErrorCode.entries, id, AssistantMessageTable.errorCode.name)

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
        settingsId = settingsId,
        agentRoleId = agentRoleId,
        reasoningItems = reasoningItems,
        isComplete = isComplete,
        incompleteCause = incompleteCause,
        errorCode = errorCode,
        errorMessage = this.getOrNull(AssistantMessageTable.errorMessage)
    )
}

/**
 * Resolves a stored enum name to its enum value without ever failing the read.
 *
 * An unknown or blank value (data written by a newer/older version, or a foreign write) is logged and mapped to
 * `null`, which callers interpret as "no cause"/"no code", so a single corrupt row cannot break session loading.
 *
 * @receiver The stored enum name read from [columnName], or `null` when the column held no value.
 * @param entries The enum values to search, in declaration order.
 * @param messageId The assistant message the value belongs to, used for diagnostics.
 * @param columnName The column name the value was read from, used for diagnostics.
 * @return The matching enum value, or `null` when the stored value is absent or unknown.
 */
private fun <T : Enum<T>> String?.toEnumByNameOrNull(
    entries: List<T>,
    messageId: Long,
    columnName: String
): T? {
    val storedValue = this?.takeIf { it.isNotBlank() } ?: return null
    val resolved = entries.firstOrNull { it.name == storedValue }
    if (resolved == null) {
        logger.warn(
            "Ignoring unknown value '{}' in assistant_messages.{} for message {}",
            storedValue,
            columnName,
            messageId
        )
    }
    return resolved
}
