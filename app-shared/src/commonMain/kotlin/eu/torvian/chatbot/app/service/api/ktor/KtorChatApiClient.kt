package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.resources.MessageResource
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.UpdateMessageRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [ChatApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiClient.safeApiCall] helper
 * to interact with the backend's chat message endpoints, mapping responses
 * to [Either<ApiError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorChatApiClient(client: HttpClient) : BaseApiClient(client), ChatApi {

    override suspend fun processNewMessage(
        sessionId: Long,
        request: ProcessNewMessageRequest
    ): Either<ApiError, List<ChatMessage>> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions/{sessionId}/messages
            client.post(SessionResource.ById.Messages(SessionResource.ById(SessionResource(), sessionId))) {
                // Set the request body with the ProcessNewMessageRequest DTO
                setBody(request)
            }.body<List<ChatMessage>>() // Expect a List<ChatMessage> in the response body on success (HTTP 201)
        }
    }

    override suspend fun updateMessageContent(
        messageId: Long,
        request: UpdateMessageRequest
    ): Either<ApiError, ChatMessage> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/messages/{messageId}/content
            client.put(MessageResource.ById.Content(MessageResource.ById(MessageResource(), messageId))) {
                // Set the request body with the UpdateMessageRequest DTO
                setBody(request)
            }.body<ChatMessage>() // Expect a ChatMessage in the response body on success (HTTP 200)
        }
    }

    override suspend fun deleteMessage(messageId: Long): Either<ApiError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/messages/{messageId}
            client.delete(MessageResource.ById(MessageResource(), messageId))
                // The backend should return HTTP 204 No Content on success.
                // Ktor's body<Unit>() can be used, or simply letting the request complete
                // indicates success for Unit return type in safeApiCall.
                .body<Unit>() // Explicitly expect Unit body on success
        }
    }
}