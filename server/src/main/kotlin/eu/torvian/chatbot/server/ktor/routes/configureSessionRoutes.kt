package eu.torvian.chatbot.server.ktor.routes

import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.models.api.core.*
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.ChatService
import eu.torvian.chatbot.server.service.core.MessageService
import eu.torvian.chatbot.server.service.core.SessionService
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.toApiError
import eu.torvian.chatbot.server.service.core.error.session.*
import eu.torvian.chatbot.server.service.core.toChatEvent
import eu.torvian.chatbot.server.service.core.toChatStreamEvent
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Configures routes related to Sessions (/api/v1/sessions) using Ktor Resources.
 */
fun Route.configureSessionRoutes(
    sessionService: SessionService,
    chatService: ChatService,
    authorizationService: AuthorizationService,
    json: Json
) {
    val logger: Logger = LogManager.getLogger("SessionRoutes")

    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/sessions - List all sessions for authenticated user
        get<SessionResource> {
            val userId = call.getUserId()
            call.respond(sessionService.getAllSessionsSummaries(userId))
        }

        // POST /api/v1/sessions - Create a new session
        post<SessionResource> {
            val userId = call.getUserId()
            val request = call.receive<CreateSessionRequest>()

            val result = either {
                withError({ e: CreateSessionError -> e.toApiError() }) {
                    sessionService.createSession(userId, request.name).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        // GET /api/v1/sessions/{sessionId} - Get session by ID with ownership check
        get<SessionResource.ById> { resource ->
            val userId = call.getUserId()
            val sessionId = resource.sessionId
            val result = either {
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.READ)
                withError({ error: GetSessionDetailsError -> error.toApiError() }) {
                    sessionService.getSessionDetails(sessionId).bind()
                }
            }
            call.respondEither(result)
        }

        // DELETE /api/v1/sessions/{sessionId} - Delete session by ID
        delete<SessionResource.ById> { resource ->
            val sessionId = resource.sessionId
            val userId = call.getUserId()
            val result = either {
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)
                withError({ e: DeleteSessionError -> e.toApiError() }) {
                    sessionService.deleteSession(sessionId).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        // PUT /api/v1/sessions/{sessionId}/name - Update the name of a session
        put<SessionResource.ById.Name> { resource ->
            val sessionId = resource.parent.sessionId
            val userId = call.getUserId()
            val request = call.receive<UpdateSessionNameRequest>()
            val result = either {
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)
                withError({ e: UpdateSessionNameError -> e.toApiError() }) {
                    sessionService.updateSessionName(sessionId, request.name).bind()
                }
            }
            call.respondEither(result)
        }

        // PUT /api/v1/sessions/{sessionId}/model - Update the current model ID of a session
        put<SessionResource.ById.Model> { resource ->
            val sessionId = resource.parent.sessionId
            val userId = call.getUserId()
            val request = call.receive<UpdateSessionModelRequest>()
            val result = either {
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)
                request.modelId?.let { modelId ->
                    requireModelAccess(authorizationService, userId, modelId, AccessMode.READ)
                }
                withError({ e: UpdateSessionCurrentModelIdError -> e.toApiError() }) {
                    sessionService.updateSessionCurrentModelId(sessionId, request.modelId).bind()
                }
            }
            call.respondEither(result)
        }

        // PUT /api/v1/sessions/{sessionId}/settings - Update the current settings ID of a session
        put<SessionResource.ById.Settings> { resource ->
            val sessionId = resource.parent.sessionId
            val userId = call.getUserId()
            val request = call.receive<UpdateSessionSettingsRequest>()
            val result = either {
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)
                request.settingsId?.let { settingsId ->
                    requireSettingsAccess(authorizationService, userId, settingsId, AccessMode.READ)
                }
                withError({ e: UpdateSessionCurrentSettingsIdError -> e.toApiError() }) {
                    sessionService.updateSessionCurrentSettingsId(sessionId, request.settingsId).bind()
                }
            }
            call.respondEither(result)
        }

        // PUT /api/v1/sessions/{sessionId}/leafMessage - Update the current leaf message ID of a session
        put<SessionResource.ById.LeafMessage> { resource ->
            val sessionId = resource.parent.sessionId
            val userId = call.getUserId()
            val request = call.receive<UpdateSessionLeafMessageRequest>()
            val result = either {
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)
                withError({ e: UpdateSessionLeafMessageIdError -> e.toApiError() }) {
                    sessionService.updateSessionLeafMessageId(sessionId, request.leafMessageId).bind()
                }
            }
            call.respondEither(result)
        }

        // PUT /api/v1/sessions/{sessionId}/group - Assign session to group or ungroup
        put<SessionResource.ById.Group> { resource ->
            val sessionId = resource.parent.sessionId
            val userId = call.getUserId()
            val request = call.receive<UpdateSessionGroupRequest>()
            val result = either {
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)
                request.groupId?.let { groupId ->
                    requireGroupAccess(authorizationService, userId, groupId, AccessMode.READ)
                }
                withError({ e: UpdateSessionGroupIdError -> e.toApiError() }) {
                    sessionService.updateSessionGroupId(sessionId, request.groupId).bind()
                }
            }
            call.respondEither(result)
        }

        // POST /api/v1/sessions/{sessionId}/messages - Process a new message for a session
        post<SessionResource.ById.Messages> { resource ->
            val userId = call.getUserId()
            val sessionId = resource.parent.sessionId
            val request = call.receive<ProcessNewMessageRequest>()
            val parentMessageId = request.parentMessageId
            val isStreaming = request.isStreaming

            // Step 1: Perform validation.
            val validationResult = either {
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)
                withError({ validateError: ValidateNewMessageError ->
                    validateError.toApiError()
                }) {
                    chatService.validateProcessNewMessageRequest(
                        sessionId,
                        parentMessageId,
                        isStreaming
                    ).bind()
                }
            }

            // Step 2: Respond with SSE.
            call.respond(SSEServerContent(call, handle = {
                val (session, llmConfig) = validationResult.getOrElse { apiError ->
                    logger.error("Validation failed for session $sessionId: $apiError")
                    // Send a single SSE 'error' event and close the stream.
                    if (isStreaming) {
                        val errorEvent: ChatStreamEvent = ChatStreamEvent.ErrorOccurred(apiError)
                        send(
                            ServerSentEvent(
                                event = errorEvent.eventType,
                                data = json.encodeToString(errorEvent)
                            )
                        )
                    } else {
                        val errorEvent: ChatEvent = ChatEvent.ErrorOccurred(apiError)
                        send(
                            ServerSentEvent(
                                event = errorEvent.eventType,
                                data = json.encodeToString(errorEvent)
                            )
                        )
                    }
                    return@SSEServerContent
                }
                if (isStreaming) {
                    try {
                        chatService.processNewMessageStreaming(
                            session,
                            llmConfig,
                            request.content,
                            parentMessageId
                        ).collect { eitherStreamEvent ->
                            eitherStreamEvent
                                .onLeft { streamError ->
                                    val apiError = streamError.toApiError()
                                    val chatStreamEvent: ChatStreamEvent =
                                        ChatStreamEvent.ErrorOccurred(apiError)
                                    send(
                                        ServerSentEvent(
                                            event = "error",
                                            data = json.encodeToString(chatStreamEvent)
                                        )
                                    )
                                }
                                .onRight { streamEvent ->
                                    val chatStreamEvent = streamEvent.toChatStreamEvent()
                                    send(
                                        ServerSentEvent(
                                            event = chatStreamEvent.eventType,
                                            data = json.encodeToString(chatStreamEvent)
                                        )
                                    )
                                }
                        }
                    } catch (e: Exception) {
                        logger.error(
                            "Unexpected error during streaming message processing for session $sessionId: ${e.message}",
                            e
                        )
                        val chatStreamEvent: ChatStreamEvent = ChatStreamEvent.ErrorOccurred(
                            apiError(
                                CommonApiErrorCodes.INTERNAL,
                                "An unexpected error occurred during streaming."
                            )
                        )
                        send(
                            ServerSentEvent(
                                event = chatStreamEvent.eventType,
                                data = json.encodeToString(chatStreamEvent)
                            )
                        )
                    }
                } else {
                    // Non-streaming processing
                    try {
                        chatService.processNewMessage(session, llmConfig, request.content, parentMessageId)
                            .collect { eitherEvent ->
                                eitherEvent
                                    .onLeft { messageError ->
                                        val chatEvent: ChatEvent = ChatEvent.ErrorOccurred(messageError.toApiError())
                                        send(
                                            ServerSentEvent(
                                                event = chatEvent.eventType,
                                                data = json.encodeToString(chatEvent)
                                            )
                                        )
                                    }
                                    .onRight { messageEvent ->
                                        val chatEvent = messageEvent.toChatEvent()
                                        send(
                                            ServerSentEvent(
                                                event = chatEvent.eventType,
                                                data = json.encodeToString(chatEvent)
                                            )
                                        )
                                    }
                            }
                    } catch (e: Exception) {
                        logger.error(
                            "Unexpected error during non-streaming message processing for session $sessionId: ${e.message}",
                            e
                        )
                        val chatEvent: ChatEvent = ChatEvent.ErrorOccurred(
                            apiError(
                                CommonApiErrorCodes.INTERNAL,
                                "An unexpected error occurred during non-streaming processing."
                            )
                        )
                        send(
                            ServerSentEvent(
                                event = chatEvent.eventType,
                                data = json.encodeToString(chatEvent)
                            )
                        )
                    }
                }
            }))
        }
    } // End authenticate block
}
