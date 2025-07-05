package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.service.core.MessageService
import eu.torvian.chatbot.server.service.core.SessionService
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.session.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route

/**
 * Configures routes related to Sessions (/api/v1/sessions) using Ktor Resources.
 */
fun Route.configureSessionRoutes(
    sessionService: SessionService,
    messageService: MessageService
) {
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
        val sessionId = resource.parent.sessionId
        val request = call.receive<ProcessNewMessageRequest>()
        call.respondEither(
            messageService.processNewMessage(
                sessionId,
                request.content,
                request.parentMessageId
            ), HttpStatusCode.Created
        ) { error ->
            when (error) {
                is ProcessNewMessageError.SessionNotFound ->
                    apiError(
                        CommonApiErrorCodes.NOT_FOUND,
                        "Session not found",
                        "sessionId" to error.sessionId.toString()
                    )

                is ProcessNewMessageError.ParentNotInSession ->
                    apiError(
                        CommonApiErrorCodes.INVALID_STATE,
                        "Parent message does not belong to this session",
                        "sessionId" to error.sessionId.toString(),
                        "parentId" to error.parentId.toString()
                    )

                is ProcessNewMessageError.ModelConfigurationError ->
                    apiError(
                        ChatbotApiErrorCodes.MODEL_CONFIGURATION_ERROR,
                        "LLM configuration error",
                        "details" to error.message
                    )

                is ProcessNewMessageError.ExternalServiceError ->
                    apiError(ChatbotApiErrorCodes.EXTERNAL_SERVICE_ERROR, "LLM API Error", "details" to error.llmError.toString())
            }
        }
    }
}