package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.server.service.core.error.message.DeleteMessageError
import eu.torvian.chatbot.server.service.core.error.message.GetMessagesError
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.UpdateMessageContentError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import kotlinx.coroutines.flow.Flow

/**
 * Service interface for managing Chat Messages and their threading relationships.
 * Contains core business logic for message processing and modification.
 */
interface MessageService {
    /**
     * Retrieves a list of all messages for a specific session, ordered by creation time.
     * Verifies that the user owns the session before returning messages.
     * @param userId The ID of the user requesting the messages.
     * @param sessionId The ID of the session.
     * @return Either a [GetMessagesError] if the session doesn't exist or access is denied,
     *         or a list of [ChatMessage] objects.
     */
    suspend fun getMessagesBySessionId(userId: Long, sessionId: Long): Either<GetMessagesError, List<ChatMessage>>

    suspend fun validateProcessNewMessageRequest(
        userId: Long,
        sessionId: Long,
        parentMessageId: Long?
    ): Either<ValidateNewMessageError, Pair<ChatSession, LLMConfig>>

    /**
     * Processes a new incoming user message and awaits the full LLM response.
     * The assistant message is saved only after the full response is received.
     *
     * @param session The session the message belongs to.
     * @param llmConfig The LLM configuration to use for the request.
     * @param content The user's message content.
     * @param parentMessageId Optional ID of the message being replied to (null for root messages).
     * @return Either a [ProcessNewMessageError] if processing fails,
     *         or a list containing the newly created user and assistant messages ([userMsg, assistantMsg]).
     */
    suspend fun processNewMessage(
        session: ChatSession,
        llmConfig: LLMConfig,
        content: String,
        parentMessageId: Long? = null
    ): Either<ProcessNewMessageError, List<ChatMessage>>

    /**
     * Processes a new incoming user message and streams the LLM response.
     * The user message is saved immediately. The assistant message is saved only upon successful completion of the stream.
     *
     * @param session The session the message belongs to.
     * @param llmConfig The LLM configuration to use for the request.
     * @param content The user's message content.
     * @param parentMessageId Optional ID of the message being replied to.
     * @return A Flow of Either<ProcessNewMessageError, MessageStreamEvent>.
     *         The flow emits `MessageStreamEvent` events (user message, assistant start, deltas, end)
     *         or `ProcessNewMessageError` if an error occurs during streaming.
     */
    fun processNewMessageStreaming(
        session: ChatSession,
        llmConfig: LLMConfig,
        content: String,
        parentMessageId: Long? = null
    ): Flow<Either<ProcessNewMessageError, MessageStreamEvent>>

    /**
     * Updates the content of an existing message.
     * Verifies that the user owns the session containing the message before updating.
     * @param userId The ID of the user requesting the update.
     * @param id The ID of the message to update.
     * @param content The new content.
     * @return Either an [UpdateMessageContentError] if the message doesn't exist or access is denied,
     *         or the updated [ChatMessage].
     */
    suspend fun updateMessageContent(
        userId: Long,
        id: Long,
        content: String
    ): Either<UpdateMessageContentError, ChatMessage>

    /**
     * Deletes a specific message and its children recursively.
     * Verifies that the user owns the session containing the message before deleting.
     * Updates the parent's children list and maintains session leaf message consistency.
     *
     * When a message is deleted, the session's currentLeafMessageId is automatically updated:
     * - If the deleted message is not in the current leaf's path, no change is made
     * - If a non-root message is deleted, the parent becomes the new leaf or the first remaining sibling's leaf
     * - If a root message is deleted, the oldest remaining root message's leaf becomes active
     * - If no messages remain, the session's leaf message ID is set to null
     *
     * @param userId The ID of the user requesting the deletion.
     * @param id The ID of the message to delete.
     * @return Either a [DeleteMessageError] if the message doesn't exist, access is denied, or session update fails,
     *         or Unit if successful.
     */
    suspend fun deleteMessageRecursively(userId: Long, id: Long): Either<DeleteMessageError, Unit>

    /**
     * Deletes a single message (non-recursive) and promotes its children to the deleted message's parent.
     * Verifies that the user owns the session containing the message before deleting.
     * Updates session currentLeafMessageId if needed.
     *
     * @param userId The ID of the user requesting the deletion.
     * @param id The ID of the message to delete.
     * @return Either a [DeleteMessageError] if the message doesn't exist, access is denied, or session update fails,
     *         or Unit if successful.
     */
    suspend fun deleteMessage(userId: Long, id: Long): Either<DeleteMessageError, Unit>
}
