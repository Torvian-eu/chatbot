package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatStreamEvent
import eu.torvian.chatbot.common.models.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.UpdateMessageRequest
import kotlinx.coroutines.flow.Flow

/**
 * Frontend API interface for interacting with Chat message-related endpoints.
 *
 * This interface defines the operations available for managing individual messages
 * and processing new messages within a session. Implementations use the internal HTTP API.
 * All methods are suspend functions and return [Either<ApiError, T>] to explicitly
 * handle potential API errors.
 */
interface ChatApi {

    /**
     * Sends a new user message to a specified session and processes the LLM response.
     * This is for **non-streaming** responses.
     *
     * Corresponds to `POST /api/v1/sessions/{sessionId}/messages` with `stream=false`.
     *
     * @param sessionId The ID of the session to send the message to.
     * @param request The details of the new message, including content and optional parent ID.
     * @return [arrow.core.Either.Right] containing a list with the newly created user and assistant messages (in that order) on success,
     *         or [Either.Left] containing an [ApiError] on failure.
     */
    suspend fun processNewMessage(sessionId: Long, request: ProcessNewMessageRequest): Either<ApiError, List<ChatMessage>>

    /**
     * Sends a new user message to a specified session and streams the LLM response back.
     *
     * Corresponds to `POST /api/v1/sessions/{sessionId}/messages` with `stream=true`.
     *
     * @param sessionId The ID of the session to send the message to.
     * @param request The details of the new message.
     * @return A [Flow] of [Either<ApiError, ChatStreamEvent>] representing the stream of updates.
     *         The flow will emit various [ChatStreamEvent] types until [ChatStreamEvent.StreamCompleted] or an error.
     */
    fun processNewMessageStreaming(sessionId: Long, request: ProcessNewMessageRequest): Flow<Either<ApiError, ChatStreamEvent>>

    /**
     * Updates the content of an existing message.
     *
     * Corresponds to `PUT /api/v1/messages/{messageId}/content`.
     * (E3.S3)
     *
     * @param messageId The ID of the message to update.
     * @param request The request body containing the new content.
     * @return [Either.Right] containing the updated [ChatMessage] object on success,
     *         or [Either.Left] containing an [ApiError] on failure.
     */
    suspend fun updateMessageContent(messageId: Long, request: UpdateMessageRequest): Either<ApiError, ChatMessage>

    /**
     * Deletes a specific message and its children.
     *
     * Corresponds to `DELETE /api/v1/messages/{messageId}`.
     * (E3.S4)
     *
     * @param messageId The ID of the message to delete.
     * @return [Either.Right] with [Unit] on successful deletion (typically HTTP 204 No Content),
     *         or [Either.Left] containing an [ApiError] on failure.
     */
    suspend fun deleteMessage(messageId: Long): Either<ApiError, Unit>
}