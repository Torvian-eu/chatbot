package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.server.data.dao.error.InsertMessageError
import eu.torvian.chatbot.server.data.dao.error.MessageError

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
     * Inserts a new user message and automatically handles parent-child relationships.
     * If parentMessageId is provided, adds this message as a child to the parent.
     *
     * @param sessionId The ID of the session the message belongs to.
     * @param content The text content of the message.
     * @param parentMessageId Optional ID of the parent message (null for root messages).
     * @return Either a [InsertMessageError] or the newly created [ChatMessage.UserMessage].
     */
    suspend fun insertUserMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long?
    ): Either<InsertMessageError, ChatMessage.UserMessage>

    /**
     * Inserts a new assistant message and automatically handles parent-child relationships.
     * If parentMessageId is provided, adds this message as a child to the parent.
     *
     * @param sessionId The ID of the session the message belongs to.
     * @param content The text content of the message.
     * @param parentMessageId Optional ID of the parent message (null for root messages).
     * @param modelId Optional ID of the model used (for assistant messages).
     * @param settingsId Optional ID of the settings profile used (for assistant messages).
     * @return Either a [InsertMessageError] or the newly created [ChatMessage.AssistantMessage].
     */
    suspend fun insertAssistantMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long?,
        modelId: Long?,
        settingsId: Long?
    ): Either<InsertMessageError, ChatMessage.AssistantMessage>
    
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
