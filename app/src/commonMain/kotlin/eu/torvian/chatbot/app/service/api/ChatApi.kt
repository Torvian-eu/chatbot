package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
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
     * Establishes a WebSocket connection to process a new message for a session and handles non-streaming events.
     *
     * Corresponds to a WebSocket connection to `WS /api/v1/sessions/{sessionId}/messages`.
     *
     * @param sessionId The ID of the session to send the message to.
     * @param clientEvents A flow of events from the client to the server. The first event must be
     *                     [ChatClientEvent.ProcessNewMessage] with `isStreaming=false`. Subsequent events
     *                     can be [ChatClientEvent.LocalMCPToolResult] for tool execution.
     * @return A [Flow] of [Either<ApiResourceError, ChatEvent>] representing discrete events from the server.
     *         The flow will emit various [ChatEvent] types until the connection is closed.
     */
    fun processNewMessage(sessionId: Long, clientEvents: Flow<ChatClientEvent>): Flow<Either<ApiResourceError, ChatEvent>>

    /**
     * Establishes a WebSocket connection to process a new message for a session and handles streaming events.
     *
     * Corresponds to a WebSocket connection to `WS /api/v1/sessions/{sessionId}/messages`.
     *
     * @param sessionId The ID of the session to send the message to.
     * @param clientEvents A flow of events from the client to the server. The first event must be
     *                     [ChatClientEvent.ProcessNewMessage] with `isStreaming=true`. Subsequent events
     *                     can be [ChatClientEvent.LocalMCPToolResult] for tool execution.
     * @return A [Flow] of [Either<ApiResourceError, ChatStreamEvent>] representing the stream of updates from the server.
     *         The flow will emit various [ChatStreamEvent] types until the connection is closed.
     */
    fun processNewMessageStreaming(sessionId: Long, clientEvents: Flow<ChatClientEvent>): Flow<Either<ApiResourceError, ChatStreamEvent>>

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