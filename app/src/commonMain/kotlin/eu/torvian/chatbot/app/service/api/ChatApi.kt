package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.api.core.UpdateMessageRequest
import kotlinx.coroutines.flow.Flow

/**
 * Frontend API interface for interacting with Chat message-related endpoints.
 *
 * This interface defines the operations available for managing individual messages
 * and processing new messages within a session. Implementations use the internal HTTP API.
 * All methods are suspend functions and return [Either<ApiResourceError, T>] to explicitly
 * handle potential API errors.
 */
interface ChatApi {

    /**
     * Sends a new user message to a specified session and processes the LLM response.
     * This is for **non-streaming** responses, but still uses Server-Sent Events (SSE) to deliver progress updates.
     *
     * Corresponds to `POST /api/v1/sessions/{sessionId}/messages` with `stream=false`.
     *
     * @param sessionId The ID of the session to send the message to.
     * @param request The details of the new message, including content and optional parent ID.
     * @return A [Flow] of [Either<ApiResourceError, ChatEvent>] representing discrete events during message processing.
     *         The flow will emit various [ChatEvent] types until [ChatEvent.StreamCompleted] or an error.
     */
    fun processNewMessage(sessionId: Long, request: ProcessNewMessageRequest): Flow<Either<ApiResourceError, ChatEvent>>

    /**
     * Sends a new user message to a specified session and streams the LLM response back.
     *
     * Corresponds to `POST /api/v1/sessions/{sessionId}/messages` with `stream=true`.
     *
     * @param sessionId The ID of the session to send the message to.
     * @param request The details of the new message.
     * @return A [Flow] of [Either<ApiResourceError, ChatStreamEvent>] representing the stream of updates.
     *         The flow will emit various [ChatStreamEvent] types until [ChatStreamEvent.StreamCompleted] or an error.
     */
    fun processNewMessageStreaming(sessionId: Long, request: ProcessNewMessageRequest): Flow<Either<ApiResourceError, ChatStreamEvent>>

    /**
     * Updates the content of an existing message.
     *
     * Corresponds to `PUT /api/v1/messages/{messageId}/content`.
     * (E3.S3)
     *
     * @param messageId The ID of the message to update.
     * @param request The request body containing the new content.
     * @return [Either.Right] containing the updated [ChatMessage] object on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updateMessageContent(messageId: Long, request: UpdateMessageRequest): Either<ApiResourceError, ChatMessage>

    /**
     * Deletes a specific message and its children.
     *
     * Corresponds to `DELETE /api/v1/messages/{messageId}`.
     * (E3.S4)
     *
     * @param messageId The ID of the message to delete.
     * @return [Either.Right] with [Unit] on successful deletion (typically HTTP 204 No Content),
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun deleteMessage(messageId: Long): Either<ApiResourceError, Unit>
}