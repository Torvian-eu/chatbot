package eu.torvian.chatbot.server.ktor.routes

import arrow.core.getOrElse
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.service.core.MessageService
import eu.torvian.chatbot.server.service.core.MessageStreamEvent
import eu.torvian.chatbot.server.service.core.SessionService
import eu.torvian.chatbot.server.service.core.error.message.toApiError
import eu.torvian.chatbot.server.service.core.error.session.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Configures routes related to Sessions (/api/v1/sessions) using Ktor Resources.
 */
fun Route.configureSessionRoutes(
    sessionService: SessionService,
    messageService: MessageService
) {
    val json: Json by inject() // Inject the Json instance for serialization
    val logger: Logger = LogManager.getLogger("SessionRoutes")

    // GET /api/v1/sessions - List all sessions
    get<SessionResource> {
        call.respond(sessionService.getAllSessionsSummaries())
    }

    // POST /api/v1/sessions - Create a new session
    post<SessionResource> {
        val request = call.receive<CreateSessionRequest>()
        call.respondEither(
            sessionService.createSession(request.name),
            HttpStatusCode.Created
        ) { error ->
            when (error) {
                is CreateSessionError.InvalidName ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Invalid session name provided",
                        "reason" to error.reason
                    )

                is CreateSessionError.InvalidRelatedEntity ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Invalid related entity ID provided",
                        "details" to error.message
                    )
            }
        }
    }

    // GET /api/v1/sessions/{sessionId} - Get session by ID
    get<SessionResource.ById> { resource ->
        val sessionId = resource.sessionId
        call.respondEither(sessionService.getSessionDetails(sessionId)) { error ->
            when (error) {
                is GetSessionDetailsError.SessionNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())
            }
        }
    }

    // DELETE /api/v1/sessions/{sessionId} - Delete session by ID
    delete<SessionResource.ById> { resource ->
        val sessionId = resource.sessionId
        call.respondEither(
            sessionService.deleteSession(sessionId),
            HttpStatusCode.NoContent
        ) { error ->
            when (error) {
                is DeleteSessionError.SessionNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())
            }
        }
    }

    // --- Granular PUT routes using nested resources ---
    // PUT /api/v1/sessions/{sessionId}/name - Update the name of a session
    put<SessionResource.ById.Name> { resource ->
        val sessionId = resource.parent.sessionId
        val request = call.receive<UpdateSessionNameRequest>()
        call.respondEither(sessionService.updateSessionName(sessionId, request.name)) { error ->
            when (error) {
                is UpdateSessionNameError.SessionNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())

                is UpdateSessionNameError.InvalidName ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Invalid session name provided",
                        "reason" to error.reason
                    )
            }
        }
    }

    // PUT /api/v1/sessions/{sessionId}/model - Update the current model ID of a session
    put<SessionResource.ById.Model> { resource ->
        val sessionId = resource.parent.sessionId
        val request = call.receive<UpdateSessionModelRequest>()
        call.respondEither(
            sessionService.updateSessionCurrentModelId(sessionId, request.modelId)
        ) { error ->
            when (error) {
                is UpdateSessionCurrentModelIdError.SessionNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())

                is UpdateSessionCurrentModelIdError.InvalidRelatedEntity ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Invalid model ID provided",
                        "modelId" to request.modelId.toString()
                    )
            }
        }
    }

    // PUT /api/v1/sessions/{sessionId}/settings - Update the current settings ID of a session
    put<SessionResource.ById.Settings> { resource ->
        val sessionId = resource.parent.sessionId
        val request = call.receive<UpdateSessionSettingsRequest>()
        call.respondEither(
            sessionService.updateSessionCurrentSettingsId(sessionId, request.settingsId)
        ) { error ->
            when (error) {
                is UpdateSessionCurrentSettingsIdError.SessionNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())

                is UpdateSessionCurrentSettingsIdError.InvalidRelatedEntity ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Invalid settings ID provided",
                        "settingsId" to request.settingsId.toString()
                    )
            }
        }
    }

    // PUT /api/v1/sessions/{sessionId}/leafMessage - Update the current leaf message ID of a session
    put<SessionResource.ById.LeafMessage> { resource ->
        val sessionId = resource.parent.sessionId
        val request = call.receive<UpdateSessionLeafMessageRequest>()
        call.respondEither(
            sessionService.updateSessionLeafMessageId(sessionId, request.leafMessageId)
        ) { error ->
            when (error) {
                is UpdateSessionLeafMessageIdError.SessionNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())

                is UpdateSessionLeafMessageIdError.InvalidRelatedEntity ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Invalid leaf message ID provided",
                        "leafMessageId" to request.leafMessageId.toString()
                    )
            }
        }
    }

    // PUT /api/v1/sessions/{sessionId}/group - Assign session to group or ungroup
    put<SessionResource.ById.Group> { resource ->
        val sessionId = resource.parent.sessionId
        val request = call.receive<UpdateSessionGroupRequest>()
        call.respondEither(
            sessionService.updateSessionGroupId(sessionId, request.groupId)
        ) { error ->
            when (error) {
                is UpdateSessionGroupIdError.SessionNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())

                is UpdateSessionGroupIdError.InvalidRelatedEntity ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Invalid group ID provided",
                        "groupId" to request.groupId.toString()
                    )
            }
        }
    }

    // POST /api/v1/sessions/{sessionId}/messages - Process a new message for a session
    post<SessionResource.ById.Messages> { resource ->
        val sessionId = resource.parent.sessionId // Access sessionId directly from the resource object
        val request = call.receive<ProcessNewMessageRequest>()

        // Validate request before proceeding
        val (session, llmConfig) = messageService.validateProcessNewMessageRequest(
            sessionId,
            request.parentMessageId
        ).getOrElse { error ->
            val apiError = error.toApiError()
            return@post call.respond(HttpStatusCode.fromValue(apiError.statusCode), apiError)
        }

        if ((llmConfig.settings as ChatModelSettings).stream) {
            // Handle Streaming Request using SSE
            call.respond(SSEServerContent(call, handle = {
                try {
                    messageService.processNewMessageStreaming(
                        session,
                        llmConfig,
                        request.content,
                        request.parentMessageId
                    ).collect { eitherStreamEvent ->
                        eitherStreamEvent.fold(
                            ifLeft = { error ->
                                // Convert ProcessNewMessageError to ApiError and send as ChatStreamEvent.ErrorOccurred
                                val apiError = error.toApiError()
                                val chatStreamEvent = ChatStreamEvent.ErrorOccurred(apiError)
                                send(ServerSentEvent(event = "error", data = json.encodeToString(chatStreamEvent)))
                            },
                            ifRight = { streamEvent ->
                                // Map MessageStreamEvent to ChatStreamEvent and determine SSE event type
                                val chatStreamEvent = when (streamEvent) {
                                    is MessageStreamEvent.UserMessageSaved ->
                                        ChatStreamEvent.UserMessageSaved(streamEvent.message)

                                    is MessageStreamEvent.AssistantMessageStarted ->
                                        ChatStreamEvent.AssistantMessageStart(streamEvent.assistantMessage)

                                    is MessageStreamEvent.AssistantMessageDelta ->
                                        ChatStreamEvent.AssistantMessageDelta(
                                            streamEvent.messageId,
                                            streamEvent.deltaContent
                                        )

                                    is MessageStreamEvent.AssistantMessageCompleted ->
                                        ChatStreamEvent.AssistantMessageEnd(
                                            streamEvent.tempMessageId,
                                            streamEvent.finalAssistantMessage,
                                            streamEvent.finalUserMessage
                                        )

                                    is MessageStreamEvent.StreamCompleted ->
                                        ChatStreamEvent.StreamCompleted
                                }
                                // Send the ChatStreamEvent object serialized as JSON
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
                    logger.error("Unexpected error during streaming message processing for session $sessionId: ${e.message}", e)
                    // Send a generic error event to the client
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
                                )
                            )
                        )
                    )
                }
            }))
        } else {
            // Handle Non-Streaming Request
            call.respondEither(
                messageService.processNewMessage(
                    session,
                    llmConfig,
                    request.content,
                    request.parentMessageId
                ), HttpStatusCode.Created
            ) { error ->
                error.toApiError()
            }
        }
    }
}