package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.utils.misc.ioDispatcher
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.api.CommonWebSocketProtocols
import eu.torvian.chatbot.common.api.resources.DeleteMode
import eu.torvian.chatbot.common.api.resources.MessageResource
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.api.core.*
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import io.ktor.http.HttpHeaders
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Ktor HttpClient implementation of the [ChatApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's chat message endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 * @property wss Whether to use a secure wss:// connection for WebSocket connections.
 * @property webSocketAuthSubprotocolProvider Optional provider for WebSocket authentication subprotocols.
 *           Used for browser WebSocket authentication.
 */
class KtorChatApiClient(
    client: HttpClient,
    private val wss: Boolean = false,
    private val webSocketAuthSubprotocolProvider: WebSocketAuthSubprotocolProvider? = null
) : BaseApiResourceClient(client), ChatApi {
    companion object {
        private val logger = kmpLogger<KtorChatApiClient>()
    }

    private val json: Json = Json

    private fun String.redactProtocolValue(): String =
        if (length <= 20) this else "${take(10)}...(${length} chars)"

    override fun processNewMessage(
        sessionId: Long,
        clientEvents: Flow<ChatClientEvent>
    ): Flow<Either<ApiResourceError, ChatEvent>> =
        connectAndProcessMessages(sessionId, clientEvents)

    override fun processNewMessageStreaming(
        sessionId: Long,
        clientEvents: Flow<ChatClientEvent>
    ): Flow<Either<ApiResourceError, ChatStreamEvent>> =
        connectAndProcessMessages(sessionId, clientEvents)

    /**
     * Generic helper function to establish a WebSocket connection and handle the bidirectional event flow.
     *
     * @param sessionId The ID of the session to connect to.
     * @param clientEvents The outbound flow of events to send to the server.
     * @return An inbound flow of deserialized events from the server.
     */
    private inline fun <reified T : Any> connectAndProcessMessages(
        sessionId: Long,
        clientEvents: Flow<ChatClientEvent>
    ): Flow<Either<ApiResourceError, T>> = flow {
        try {
            val sessionUrl = href(SessionResource.ById.Messages(SessionResource.ById(SessionResource(), sessionId)))
            logger.info("Connecting to WebSocket: $sessionUrl")
            val providedSubprotocols = webSocketAuthSubprotocolProvider?.getSubprotocols().orEmpty()
            // For non-browser targets we still offer the marker so protocol negotiation succeeds consistently.
            val subprotocols = providedSubprotocols.ifEmpty {
                listOf(CommonWebSocketProtocols.CHATBOT_AUTH)
            }
            logger.debug("WS requested subprotocols: ${subprotocols.map { it.redactProtocolValue() }}")

            client.webSocket(path = sessionUrl, wss = wss, subprotocols = subprotocols) {
                val negotiatedSubprotocol = runCatching {
                    call.response.headers[HttpHeaders.SecWebSocketProtocol]
                }.getOrNull()
                logger.info("WebSocket negotiated subprotocol for session $sessionId: ${negotiatedSubprotocol ?: "<none>"}")

                // Launch a coroutine to send client events to the server
                val senderJob = launch {
                    clientEvents.collect { event ->
                        try {
                            val jsonString = json.encodeToString(ChatClientEvent.serializer(), event)
                            send(Frame.Text(jsonString))
                        } catch (e: Exception) {
                            logger.error("Failed to send WebSocket frame for session $sessionId", e)
                        }
                    }
                }

                try {
                    // Listen for incoming server events
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val jsonString = frame.readText()
                            try {
                                val serverEvent = json.decodeFromString<T>(jsonString)
                                emit(serverEvent.right())
                            } catch (e: Exception) {
                                logger.error("Deserialization error for incoming frame: $jsonString", e)
                                emit(
                                    ApiResourceError.SerializationError("Failed to parse server event: $jsonString", e)
                                        .left()
                                )
                            }
                        }
                    }
                } finally {
                    senderJob.cancel()
                    val closeReason = runCatching { closeReason.await() }.getOrNull()
                    logger.info("WebSocket incoming channel closed for session $sessionId, closeReason=${closeReason?.message ?: "<none>"}, closeCode=${closeReason?.code}")
                }
            }
        } catch (e: WebSocketException) {
            logger.error("WebSocket exception for session $sessionId: ${e.message}", e)
            val apiError =
                ApiResourceError.UnknownError("WebSocket handshake or transport failed.", e)
            emit(apiError.left())
        } catch (e: Exception) {
            logger.error("WebSocket connection error for session $sessionId", e)
            val apiError =
                ApiResourceError.UnknownError("An unexpected error occurred during WebSocket communication.", e)
            emit(apiError.left())
        }
    }.flowOn(ioDispatcher)

    override suspend fun updateMessageContent(
        messageId: Long,
        content: String,
        fileReferences: List<FileReference>?
    ): Either<ApiResourceError, ChatMessage> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/messages/{messageId}/content
            client.put(MessageResource.ById.Content(MessageResource.ById(MessageResource(), messageId))) {
                // Set the request body with the UpdateMessageRequest DTO
                setBody(UpdateMessageRequest(content = content, fileReferences = fileReferences))
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

    override suspend fun deleteMessageRecursively(messageId: Long): Either<ApiResourceError, Unit> {
        // Use safeApiCall to wrap the Ktor request
        return safeApiCall {
            // Use Ktor resources to build the URL: /api/v1/messages/{messageId}?mode=RECURSIVE
            client.delete(MessageResource.ById(MessageResource(), messageId, DeleteMode.RECURSIVE))
                .body<Unit>()
        }
    }

    override suspend fun insertMessage(
        sessionId: Long,
        targetMessageId: Long?,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String,
        modelId: Long?,
        settingsId: Long?,
        fileReferences: List<FileReference>
    ): Either<ApiResourceError, ChatMessage> {
        val request = InsertMessageRequest(
            sessionId = sessionId,
            targetMessageId = targetMessageId,
            position = position,
            role = role,
            content = content,
            modelId = modelId,
            settingsId = settingsId,
            fileReferences = fileReferences
        )

        return safeApiCall {
            client.post(MessageResource.Insert()) {
                setBody(request)
            }.body<ChatMessage>()
        }
    }
}