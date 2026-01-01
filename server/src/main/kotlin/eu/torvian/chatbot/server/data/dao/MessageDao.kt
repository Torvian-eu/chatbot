package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.data.dao.error.InsertMessageError
import eu.torvian.chatbot.server.data.dao.error.MessageError
import kotlinx.datetime.Instant

/**
 * Data Access Object for ChatMessage entities.
 */
interface MessageDao {
    /**
     * Retrieves all messages for a specific session, ordered by creation time.
     * The list is flat; the service/UI layer is responsible for reconstructing the thread tree.
     * Includes threading data (`parentMessageId`, `childrenMessageIds`).
     * @param sessionId The ID of the session whose messages to retrieve.
     * @return A list of all [ChatMessage] objects for the session.
     */
    suspend fun getMessagesBySessionId(sessionId: Long): List<ChatMessage>

    /**
     * Retrieves a single message by its ID.
     * Used internally or by services needing a specific message object.
     * @param id The ID of the message to retrieve.
     * @return Either [MessageError.MessageNotFound] if not found, or the [ChatMessage] object.
     */
    suspend fun getMessageById(id: Long): Either<MessageError.MessageNotFound, ChatMessage>

    /**
     * Inserts a new message.
     * Handles all re-parenting and child list updates atomically.
     *
     * @param sessionId The ID of the session.
     * @param targetMessageId The ID of the message to insert relative to. Null if inserting a root message.
     * @param position The position relative to the target (ABOVE, BELOW, or APPEND).
     *                 If targetMessageId is null, position is ignored (treated as root insert).
     * @param role The role of the new message.
     * @param content The content of the new message.
     * @param modelId Optional model ID (for assistant messages).
     * @param settingsId Optional settings ID (for assistant messages).
     * @param fileReferences Optional list of file references.
     * @param createdAt Optional creation timestamp. If null, uses current time.
     * @param updatedAt Optional update timestamp. If null, uses current time.
     * @return Either an error or the newly created message.
     */
    suspend fun insertMessage(
        sessionId: Long,
        targetMessageId: Long?,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String,
        modelId: Long?,
        settingsId: Long?,
        fileReferences: List<FileReference> = emptyList(),
        createdAt: Instant? = null,
        updatedAt: Instant? = null
    ): Either<InsertMessageError, ChatMessage>

    /**
     * Updates the content, file references, and updated timestamp of an existing message.
     * @param id The ID of the message to update.
     * @param content The new content.
     * @param fileReferences The new list of file references (optional, if null keeps existing).
     * @return Either a [MessageError.MessageNotFound] or the updated [ChatMessage] object.
     */
    suspend fun updateMessageContent(
        id: Long,
        content: String,
        fileReferences: List<FileReference>? = null
    ): Either<MessageError.MessageNotFound, ChatMessage>

    /**
     * Deletes a specific message and handles its impact on thread relationships.
     * The V1.1 strategy is to recursively delete all children of the deleted message.
     * Also removes the deleted message's ID from its parent's children list.
     * @param id The ID of the message to delete.
     * @return Either a [MessageError.MessageNotFound] or Unit if successful.
     */
    suspend fun deleteMessageRecursively(id: Long): Either<MessageError.MessageNotFound, Unit>

    /**
     * Deletes a single message without deleting its descendants.
     * Promotes all children of the deleted message to its parent (or to root if the deleted message is a root),
     * preserving child order, and updates parent/children links accordingly.
     * @param id The ID of the message to delete.
     * @return Either a [MessageError.MessageNotFound] or Unit if successful.
     */
    suspend fun deleteMessage(id: Long): Either<MessageError.MessageNotFound, Unit>
}
