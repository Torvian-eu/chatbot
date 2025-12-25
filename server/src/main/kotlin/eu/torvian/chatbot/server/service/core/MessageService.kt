package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.service.core.error.message.DeleteMessageError
import eu.torvian.chatbot.server.service.core.error.message.GetMessageError
import eu.torvian.chatbot.server.service.core.error.message.InsertMessageError
import eu.torvian.chatbot.server.service.core.error.message.UpdateMessageContentError

/**
 * Service interface for managing chat messages.
 * Contains core business logic related to messages, independent of API or data access details.
 */
interface MessageService {
    /**
     * Retrieves a list of all messages for a specific session, ordered by creation time.
     * @param sessionId The ID of the session.
     * @return A list of [ChatMessage] objects.
     */
    suspend fun getMessagesBySessionId(sessionId: Long): List<ChatMessage>

    /**
     * Retrieves a single message by its ID.
     * @param id The ID of the message to retrieve.
     * @return Either a [GetMessageError] if the message doesn't exist, or the [ChatMessage].
     */
    suspend fun getMessageById(id: Long): Either<GetMessageError, ChatMessage>

    /**
     * Updates the content of an existing message.
     * @param id The ID of the message to update.
     * @param content The new content.
     * @return Either an [UpdateMessageContentError] if the message doesn't exist,
     *         or the updated [ChatMessage].
     */
    suspend fun updateMessageContent(id: Long, content: String): Either<UpdateMessageContentError, ChatMessage>

    /**
     * Deletes a specific message and its children recursively.
     * Updates the parent's children list and maintains session leaf message consistency.
     *
     * When a message is deleted, the session's currentLeafMessageId is automatically updated:
     * - If the deleted message is not in the current leaf's path, no change is made
     * - If a non-root message is deleted, the parent becomes the new leaf or the first remaining sibling's leaf
     * - If a root message is deleted, the oldest remaining root message's leaf becomes active
     * - If no messages remain, the session's leaf message ID is set to null
     *
     * @param id The ID of the message to delete.
     * @return Either a [DeleteMessageError] if the message doesn't exist or session update fails,
     *         or Unit if successful.
     */
    suspend fun deleteMessageRecursively(id: Long): Either<DeleteMessageError, Unit>

    /**
     * Deletes a single message (non-recursive) and promotes its children to the deleted message's parent.
     * Updates session currentLeafMessageId if needed.
     *
     * @param id The ID of the message to delete.
     * @return Either a [DeleteMessageError] if the message doesn't exist or session update fails,
     *         or Unit if successful.
     */
    suspend fun deleteMessage(id: Long): Either<DeleteMessageError, Unit>

    /**
     * Inserts a new message.
     *
     * @param sessionId The ID of the session.
     * @param targetMessageId The ID of the message to insert relative to. Null if inserting a root message.
     * @param position The position relative to the target (ABOVE, BELOW, or APPEND).
     *                 If targetMessageId is null, position is ignored (treated as root insert).
     * @param role The role of the new message.
     * @param content The content of the new message.
     * @param modelId Optional model ID (for assistant messages).
     * @param settingsId Optional settings ID (for assistant messages).
     * @return Either an [InsertMessageError] or the newly created [ChatMessage].
     */
    suspend fun insertMessage(
        sessionId: Long,
        targetMessageId: Long?,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String,
        modelId: Long?,
        settingsId: Long?
    ): Either<InsertMessageError, ChatMessage>
}
