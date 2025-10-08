package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.models.api.core.*
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.MessageService
import eu.torvian.chatbot.server.service.core.MessageStreamEvent
import eu.torvian.chatbot.server.service.core.SessionService
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.toApiError
import eu.torvian.chatbot.server.service.core.error.session.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import eu.torvian.chatbot.server.service.security.ResourceType
import eu.torvian.chatbot.server.service.security.error.ResourceAuthorizationError
import eu.torvian.chatbot.server.service.security.error.toApiError
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
import org.koin.ktor.ext.inject

/**
 * Configures routes related to Sessions (/api/v1/sessions) using Ktor Resources.
 */
fun Route.configureSessionRoutes(
    sessionService: SessionService,
    messageService: MessageService,
    authorizationService: AuthorizationService
) {
    val json: Json by inject() // Inject the Json instance for serialization
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

            // Step 1: Perform validation.
            val validationResult = either {
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)
                withError({ validateError: ValidateNewMessageError ->
                    validateError.toApiError()
                }) {
                    messageService.validateProcessNewMessageRequest(
                        sessionId,
                        request.parentMessageId
                    ).bind()
                }
            }

            // Step 2: Based on the request flag, handle the validation result appropriately.
            if (request.isStreaming) {
                // --- Handle Streaming Path ---
                call.respond(SSEServerContent(call, handle = {
                    // Process the pre-computed validation result.
                    validationResult.fold(
                        ifLeft = { apiError ->
                            // Validation failed: Send a single SSE 'error' event and close the stream.
                            val errorEvent: ChatStreamEvent = ChatStreamEvent.ErrorOccurred(apiError)
                            send(ServerSentEvent(event = errorEvent.eventType, data = json.encodeToString(errorEvent)))
                        },
                        ifRight = { (session, llmConfig) ->
                            try {
                                messageService.processNewMessageStreaming(
                                    session,
                                    llmConfig,
                                    request.content,
                                    request.parentMessageId
                                ).collect { eitherStreamEvent ->
                                    eitherStreamEvent.fold(
                                        ifLeft = { streamError ->
                                            val apiError = streamError.toApiError()
                                            val chatStreamEvent: ChatStreamEvent =
                                                ChatStreamEvent.ErrorOccurred(apiError)
                                            send(
                                                ServerSentEvent(
                                                    event = "error",
                                                    data = json.encodeToString(chatStreamEvent)
                                                )
                                            )
                                        },
                                        ifRight = { streamEvent ->
                                            val chatStreamEvent = when (streamEvent) {
                                                is MessageStreamEvent.UserMessageSaved -> ChatStreamEvent.UserMessageSaved(
                                                    streamEvent.message
                                                )

                                                is MessageStreamEvent.AssistantMessageStarted -> ChatStreamEvent.AssistantMessageStart(
                                                    streamEvent.assistantMessage
                                                )

                                                is MessageStreamEvent.AssistantMessageDelta -> ChatStreamEvent.AssistantMessageDelta(
                                                    streamEvent.messageId,
                                                    streamEvent.deltaContent
                                                )

                                                is MessageStreamEvent.AssistantMessageCompleted -> ChatStreamEvent.AssistantMessageEnd(
                                                    streamEvent.tempMessageId,
                                                    streamEvent.finalAssistantMessage,
                                                    streamEvent.finalUserMessage
                                                )

                                                is MessageStreamEvent.StreamCompleted -> ChatStreamEvent.StreamCompleted
                                            }
                                            send(
                                                ServerSentEvent(
                                                    event = chatStreamEvent.eventType,
                                                    data = json.encodeToString(chatStreamEvent)
                                                )
                                            )
                                        }
                                    )
                                }
                            } catch (e: Exception) {
                                logger.error(
                                    "Unexpected error during streaming for session $sessionId: ${e.message}",
                                    e
                                )
                                send(
                                    ServerSentEvent(
                                        event = "error",
                                        data = json.encodeToString(
                                            ChatStreamEvent.ErrorOccurred(
                                                apiError(
                                                    CommonApiErrorCodes.INTERNAL,
                                                    "An unexpected error occurred during streaming.",
                                                    "details" to e.message.toString()
                                                )
                                            ) as ChatStreamEvent
                                        )
                                    )
                                )
                            }
                        }
                    )
                }))
            } else {
                // --- Handle Non-Streaming Path ---
                validationResult.fold(
                    ifLeft = { apiError ->
                        call.respond(HttpStatusCode.fromValue(apiError.statusCode), apiError)
                    },
                    ifRight = { (session, llmConfig) ->
                        call.respondEither(
                            messageService.processNewMessage(
                                session,
                                llmConfig,
                                request.content,
                                request.parentMessageId
                            ),
                            HttpStatusCode.Created
                        ) { processError ->
                            processError.toApiError()
                        }
                    }
                )
            }
        }
    } // End authenticate block
}

private suspend inline fun Raise<ApiError>.requireSessionAccess(
    authorizationService: AuthorizationService,
    userId: Long,
    sessionId: Long,
    accessMode: AccessMode
) =
    withError({ rae: ResourceAuthorizationError -> rae.toApiError() }) {
        authorizationService.requireAccess(userId, ResourceType.SESSION, sessionId, accessMode).bind()
    }
