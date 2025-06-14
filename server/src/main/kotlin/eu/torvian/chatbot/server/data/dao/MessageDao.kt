package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.MessageAddChildError
import eu.torvian.chatbot.server.data.dao.error.MessageRemoveChildError

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
     * Inserts a new user message record into the database.
     * Saves content, session ID, parent ID, and initializes other fields.
     * Children list is initialized as empty.
     * @param sessionId The ID of the session the message belongs to.
     * @param content The text content of the message.
     * @param parentMessageId Optional ID of the parent message (null for root messages).
     * @return Either a [MessageError.ForeignKeyViolation] or the newly created [ChatMessage] object.
     */
    suspend fun insertUserMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long?
    ): Either<MessageError.ForeignKeyViolation, ChatMessage>

    /**
     * Inserts a new assistant message record into the database.
     * Saves content, session ID, parent ID, and optional model/settings IDs.
     * Children list is initialized as empty.
     * @param sessionId The ID of the session the message belongs to.
     * @param content The text content of the message.
     * @param parentMessageId Optional ID of the parent message (null for root messages).
     * @param modelId Optional ID of the model used (for assistant messages).
     * @param settingsId Optional ID of the settings profile used (for assistant messages).
     * @return Either a [MessageError.ForeignKeyViolation] or the newly created [ChatMessage] object.
     */
    suspend fun insertAssistantMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long?,
        modelId: Long?,
        settingsId: Long?
    ): Either<MessageError.ForeignKeyViolation, ChatMessage>
    
    /**
     * Updates the content and updated timestamp of an existing message.
     * @param id The ID of the message to update.
     * @param content The new content.
     * @return Either a [MessageError.MessageNotFound] or the updated [ChatMessage] object.
     */
    suspend fun updateMessageContent(id: Long, content: String): Either<MessageError.MessageNotFound, ChatMessage>
    
    /**
     * Deletes a specific message and handles its impact on thread relationships.
     * The V1.1 strategy is to recursively delete all children of the deleted message.
     * Also removes the deleted message's ID from its parent's children list.
     * @param id The ID of the message to delete.
     * @return Either a [MessageError.MessageNotFound] or Unit if successful.
     */
    suspend fun deleteMessage(id: Long): Either<MessageError.MessageNotFound, Unit>
    
    /**
     * Adds a child message ID to the `childrenMessageIds` list of the parent message record.
     * Serializes the updated list back to the database.
     * Used when a new message is inserted as a reply.
     * @param parentId The ID of the parent message.
     * @param childId The ID of the new child message to add to the parent's list.
     * @return Either a [MessageAddChildError] or Unit if successful.
     */
    suspend fun addChildToMessage(parentId: Long, childId: Long): Either<MessageAddChildError, Unit>
    
    /**
     * Removes a child message ID from the `childrenMessageIds` list of the parent message record.
     * Serializes the updated list back to the database.
     * Used when a child message is deleted.
     * @param parentId The ID of the parent message.
     * @param childId The ID of the child message to remove from the parent's list.
     * @return Either a [MessageRemoveChildError] or Unit if successful.
     */
    suspend fun removeChildFromMessage(parentId: Long, childId: Long): Either<MessageRemoveChildError, Unit>
}
