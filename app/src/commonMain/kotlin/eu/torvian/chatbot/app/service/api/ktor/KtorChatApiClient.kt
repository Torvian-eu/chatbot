package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.MessageResource
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatStreamEvent
import eu.torvian.chatbot.common.models.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.UpdateMessageRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

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
    companion object {
        private val logger = kmpLogger<KtorChatApiClient>()
    }

    private val json: Json = Json

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
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS // Allow indefinite stream
                }
            }.body<List<ChatMessage>>() // Expect a List<ChatMessage> in the response body on success (HTTP 201)
        }
    }

    override fun processNewMessageStreaming(
        sessionId: Long,
        request: ProcessNewMessageRequest
    ): Flow<Either<ApiError, ChatStreamEvent>> = flow {
        try {
            client.sse(
                urlString = href(SessionResource.ById.Messages(SessionResource.ById(SessionResource(), sessionId))),
                deserialize = { typeInfo, jsonString ->
                    json.decodeFromString(json.serializersModule.serializer(typeInfo.kotlinType!!), jsonString)
                },
                request = {
                    method = HttpMethod.Post
                    setBody(request)
                    contentType(ContentType.Application.Json)
                }
            ) {
                incoming.collect { event ->
                    val chatStreamEvent = deserialize<ChatStreamEvent>(event.data)
                    if (chatStreamEvent == null) {
                        logger.error("Failed to deserialize SSE data chunk for session $sessionId: '${event.data}'")
                        emit(
                            apiError(
                                apiCode = CommonApiErrorCodes.INTERNAL,
                                message = "Failed to parse SSE data chunk",
                                "originalData" to event.data.toString()
                            ).left()
                        )
                        return@collect
                    }
                    // Emit the deserialized ChatStreamEvent
                    emit(chatStreamEvent.right())
                }
            }
        } catch (e: Exception) {
            logger.error("Network or streaming error during processNewMessageStreaming for session $sessionId", e)
            // Map common Ktor exceptions to ApiError type
            val apiError = when (e) {
                // ClientRequestException and ServerResponseException indicate
                // a non-2xx status on the *initial* connection attempt for SSE.
                is ClientRequestException -> ApiError(
                    e.response.status.value,
                    "client-error",
                    "HTTP Client Error: ${e.response.status.description}",
                    mapOf("details" to (e.response.bodyAsText()))
                )

                is ServerResponseException -> ApiError(
                    e.response.status.value,
                    "server-error",
                    "HTTP Server Error: ${e.response.status.description}",
                    mapOf("details" to (e.response.bodyAsText()))
                )

                else -> apiError(
                    500,
                    "network-error",
                    "Network or communication error during streaming: ${e.message}",
                    "error" to (e.message ?: "Unknown network error")
                )
            }
            emit(apiError.left())
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