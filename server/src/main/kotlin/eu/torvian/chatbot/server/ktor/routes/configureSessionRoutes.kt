package eu.torvian.chatbot.server.ktor.routes

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
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.koin.ktor.ext.inject

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

                is UpdateSessionCurrentModelIdError.InvalidModelType ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Model type must be CHAT",
                        "modelId" to error.modelId.toString(),
                        "actualType" to error.actualType
                    )

                is UpdateSessionCurrentModelIdError.DeprecatedModel ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Model is deprecated and cannot be used",
                        "modelId" to error.modelId.toString()
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

                is UpdateSessionCurrentSettingsIdError.SettingsModelMismatch ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Settings are not compatible with the current model",
                        "settingsId" to error.settingsId.toString(),
                        "settingsModelId" to error.settingsModelId.toString(),
                        "sessionModelId" to (error.sessionModelId?.toString() ?: "null")
                    )

                is UpdateSessionCurrentSettingsIdError.InvalidSettingsType ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Settings must be of ChatModelSettings type for chat sessions",
                        "settingsId" to error.settingsId.toString(),
                        "actualType" to error.actualType
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
        val sessionId = resource.parent.sessionId
        val request = call.receive<ProcessNewMessageRequest>()

        // Step 1: Perform validation.
        val validationResult = messageService.validateProcessNewMessageRequest(
            sessionId,
            request.parentMessageId
        )

        // Step 2: Based on the request flag, handle the validation result appropriately.
        if (request.isStreaming) {
            // --- Handle Streaming Path ---
            call.respond(SSEServerContent(call, handle = {
                // Process the pre-computed validation result.
                validationResult.fold(
                    ifLeft = { error ->
                        // Validation failed: Send a single SSE 'error' event and close the stream.
                        val apiError = error.toApiError()
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
                                        val chatStreamEvent: ChatStreamEvent = ChatStreamEvent.ErrorOccurred(apiError)
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
                            logger.error("Unexpected error during streaming for session $sessionId: ${e.message}", e)
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
                ifLeft = { error ->
                    val apiError = error.toApiError()
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
}