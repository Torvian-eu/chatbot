package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.server.service.core.error.message.DeleteMessageError
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.UpdateMessageContentError

/**
 * Service interface for managing Chat Messages and their threading relationships.
 * Contains core business logic for message processing and modification.
 */
interface MessageService {
    /**
     * Retrieves a list of all messages for a specific session, ordered by creation time.
     * @param sessionId The ID of the session.
     * @return A list of [ChatMessage] objects.
     */
    suspend fun getMessagesBySessionId(sessionId: Long): List<ChatMessage>

    /**
     * Processes a new incoming user message (including replies).
     *
     * Orchestrates saving the user message, building LLM context (thread-aware),
     * calling the LLM, saving the assistant message, and
     * updating thread relationships in the database.
     *
     * @param sessionId The ID of the session the message belongs to.
     * @param content The user's message content.
     * @param parentMessageId Optional ID of the message being replied to (null for root messages).
     * @return Either a [ProcessNewMessageError] if processing fails (e.g., session/parent not found, LLM config error, LLM API error),
     *         or a list containing the newly created user and assistant messages ([userMsg, assistantMsg]).
     */
    suspend fun processNewMessage(
        sessionId: Long,
        content: String,
        parentMessageId: Long? = null
    ): Either<ProcessNewMessageError, List<ChatMessage>>

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
     * Updates the parent's children list.
     * @param id The ID of the message to delete.
     * @return Either a [DeleteMessageError] if the message doesn't exist, or Unit if successful.
     */
    suspend fun deleteMessage(id: Long): Either<DeleteMessageError, Unit>
}
