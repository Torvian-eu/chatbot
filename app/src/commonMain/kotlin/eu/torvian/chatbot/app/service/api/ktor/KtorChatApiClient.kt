package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.utils.misc.ioDispatcher
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.api.resources.MessageResource
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.api.core.UpdateMessageRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Ktor HttpClient implementation of the [ChatApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's chat message endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorChatApiClient(client: HttpClient) : BaseApiResourceClient(client), ChatApi {
    companion object {
        private val logger = kmpLogger<KtorChatApiClient>()
    }

    private val json: Json = Json

    override suspend fun processNewMessage(
        sessionId: Long,
        request: ProcessNewMessageRequest
    ): Either<ApiResourceError, List<ChatMessage>> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/sessions/{sessionId}/messages
            client.post(SessionResource.ById.Messages(SessionResource.ById(SessionResource(), sessionId))) {
                // Set the request body with the ProcessNewMessageRequest DTO
                setBody(request)
                timeout {
                    requestTimeoutMillis =
                        HttpTimeoutConfig.INFINITE_TIMEOUT_MS // Allow indefinite timeout (for long LLM responses)
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
            }.body<List<ChatMessage>>() // Expect a List<ChatMessage> in the response body on success (HTTP 201)
        }
    }

    override fun processNewMessageStreaming(
        sessionId: Long,
        request: ProcessNewMessageRequest
    ): Flow<Either<ApiResourceError, ChatStreamEvent>> = flow {
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
                    try {
                        val chatStreamEvent = deserialize<ChatStreamEvent>(event.data)
                        if (chatStreamEvent == null) {
                            logger.error("Failed to deserialize SSE data chunk for session $sessionId: '${event.data}'")
                            emit(
                                ApiResourceError.SerializationError(
                                    "Failed to parse SSE data chunk: ${event.data}",
                                    null
                                ).left()
                            )
                            return@collect
                        }
                        emit(chatStreamEvent.right())
                    } catch (e: SerializationException) {
                        logger.error("SerializationException during SSE deserialization for session $sessionId: '${event.data}'", e)
                        emit(
                            ApiResourceError.SerializationError(
                                "Serialization error: ${e.message} for data: ${event.data}",
                                null
                            ).left()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Network or streaming error during processNewMessageStreaming for session $sessionId", e)
            val apiResourceError = when (e) {
                is SSEClientException -> ApiResourceError.NetworkError(
                    "SSE Client Error: ${e.message}",
                    e
                )

                else -> ApiResourceError.UnknownError(
                    "Unexpected error during SSE request: ${e.message}",
                    e
                )
            }
            emit(apiResourceError.left())
        }
    }.flowOn(ioDispatcher)

    override suspend fun updateMessageContent(
        messageId: Long,
        request: UpdateMessageRequest
    ): Either<ApiResourceError, ChatMessage> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/messages/{messageId}/content
            client.put(MessageResource.ById.Content(MessageResource.ById(MessageResource(), messageId))) {
                // Set the request body with the UpdateMessageRequest DTO
                setBody(request)
            }.body<ChatMessage>() // Expect a ChatMessage in the response body on success (HTTP 200)
        }
    }

    override suspend fun deleteMessage(messageId: Long): Either<ApiResourceError, Unit> {
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