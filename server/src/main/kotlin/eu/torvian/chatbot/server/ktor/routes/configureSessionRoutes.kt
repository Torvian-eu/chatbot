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
import eu.torvian.chatbot.server.ktor.mappers.toChatEvent
import eu.torvian.chatbot.server.ktor.mappers.toChatStreamEvent
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.toApiError
import eu.torvian.chatbot.server.service.core.error.session.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.websocket.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Configures routes related to Sessions (/api/v1/sessions) using Ktor Resources.
 */
fun Route.configureSessionRoutes(
    sessionService: SessionService,
    chatService: ChatService,
    toolCallService: ToolCallService,
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


//      WebSocket /api/v1/sessions/{sessionId}/messages - Process a new message for a session
        webSocket<SessionResource.ById.Messages> { resource ->
            val sessionId = resource.parent.sessionId
            val userId = call.getUserId()

            try {
                // Step 1: Receive the initial request frame from the client
                val initialFrame = incoming.receive() as? Frame.Text
                    ?: return@webSocket close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "Invalid frame type for initial request"
                        )
                    )
                val initialEvent = json.decodeFromString<ChatClientEvent>(initialFrame.readText())

                val request = (initialEvent as? ChatClientEvent.ProcessNewMessage)?.request
                    ?: return@webSocket close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "First message must be ProcessNewMessage"
                        )
                    )

                // Step 2: Perform validation
                val validationResult = either {
                    requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)
                    withError({ validateError: ValidateNewMessageError -> validateError.toApiError() }) {
                        chatService.validateProcessNewMessageRequest(
                            sessionId,
                            request.content,
                            request.parentMessageId,
                            request.isStreaming
                        ).bind()
                    }
                }

                val (session, llmConfig) = validationResult.getOrElse { apiError ->
                    logger.error("Validation failed for session $sessionId: $apiError")
                    val errorEvent: ChatEvent = ChatEvent.ErrorOccurred(apiError)
                    outgoing.send(Frame.Text(json.encodeToString(errorEvent)))
                    close(CloseReason(CloseReason.Codes.NORMAL, "Validation failed"))
                    return@webSocket
                }
                // Step 3: Create shared flow for incoming client events
                val clientEventFlow = incoming.receiveAsFlow()
                    .filterIsInstance<Frame.Text>()
                    .map { frame -> json.decodeFromString<ChatClientEvent>(frame.readText()) }
                    .shareIn(this, SharingStarted.Eagerly)

                // Step 3a: Create flow for MCP tool results
                val mcpResponseFlow = clientEventFlow
                    .filterIsInstance<ChatClientEvent.LocalMCPToolResult>()
                    .map { event -> event.result }

                // Step 3b: Create flow for tool call approval responses
                val approvalResponseFlow = clientEventFlow
                    .filterIsInstance<ChatClientEvent.ToolCallApproval>()
                    .map { event -> event.response }

                // Step 4: Start processing and stream events back to the client
                val eventFlow = if (request.isStreaming) {
                    chatService.processNewMessageStreaming(
                        userId,
                        session,
                        llmConfig,
                        request.content,
                        request.parentMessageId,
                        mcpResponseFlow,
                        approvalResponseFlow
                    )
                } else {
                    chatService.processNewMessage(
                        userId,
                        session,
                        llmConfig,
                        request.content,
                        request.parentMessageId,
                        mcpResponseFlow,
                        approvalResponseFlow
                    )
                }

                eventFlow.collect { eitherEvent ->
                    eitherEvent.fold(
                        ifLeft = { processError ->
                            val apiError = processError.toApiError()
                            val errorEvent: ChatEvent = ChatEvent.ErrorOccurred(apiError)
                            outgoing.send(Frame.Text(json.encodeToString(errorEvent)))
                        },
                        ifRight = { event ->
                            val frameData = when (event) {
                                is MessageEvent -> json.encodeToString(event.toChatEvent())
                                is MessageStreamEvent -> json.encodeToString(event.toChatStreamEvent())
                                else -> ""
                            }
                            if (frameData.isNotEmpty()) {
                                outgoing.send(Frame.Text(frameData))
                            }
                        }
                    )
                }

            } catch (e: Exception) {
                logger.error("Error in WebSocket session for session $sessionId: ${e.message}", e)
                val errorEvent =
                    ChatEvent.ErrorOccurred(apiError(CommonApiErrorCodes.INTERNAL, "An unexpected error occurred."))
                outgoing.send(Frame.Text(json.encodeToString(errorEvent)))
            } finally {
                // The WebSocket session will be closed automatically when the block finishes.
                logger.info("WebSocket session closed for session $sessionId")
            }
        }

        // GET /api/v1/sessions/{sessionId}/toolcalls - Get all tool calls for a session
        get<SessionResource.ById.ToolCalls> { resource ->
            val userId = call.getUserId()
            val sessionId = resource.parent.sessionId
            val result = either {
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.READ)
                toolCallService.getToolCallsBySessionId(sessionId)
            }
            call.respondEither(result)
        }
    } // End authenticate block
}
