package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.CommonWebSocketProtocols
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.models.api.agent.UpdateSessionAgentRoleRequest
import eu.torvian.chatbot.common.models.api.core.*
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.ktor.websocket.session.SessionMessagesWebSocketHandler
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.toApiError
import eu.torvian.chatbot.server.service.core.error.session.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/**
 * Configures routes related to Sessions (/api/v1/sessions) using Ktor Resources.
 */
fun Route.configureSessionRoutes(
    sessionService: SessionService,
    chatService: ChatService,
    toolCallService: ToolCallService,
    agentRoleService: AgentRoleService,
    authorizationService: AuthorizationService,
    json: Json
) {
    val sessionMessagesWebSocketHandler = SessionMessagesWebSocketHandler(
        chatService = chatService,
        authorizationService = authorizationService,
        json = json
    )

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

        // PUT /api/v1/sessions/{sessionId}/agentRole - Select or deselect the agent role of a session
        put<SessionResource.ById.AgentRole> { resource ->
            val sessionId = resource.parent.sessionId
            val userId = call.getUserId()
            val request = call.receive<UpdateSessionAgentRoleRequest>()
            val result = either {
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)
                // The role must exist and be owned by the requesting user before it can be attached.
                // Capture the already-fetched (per-user) DTO: reusing it for the disabled check avoids
                // an extra lookup, and a role disabled for the requesting user must not be attachable.
                val role = request.agentRoleId?.let { roleId ->
                    withError({ e: AgentRoleError -> e.toApiError() }) {
                        agentRoleService.getRoleById(userId, roleId).bind()
                    }
                }
                if (role?.disabled == true) {
                    raise(UpdateSessionAgentRoleIdError.AgentRoleDisabled(role.id).toApiError())
                }
                withError({ e: UpdateSessionAgentRoleIdError -> e.toApiError() }) {
                    sessionService.updateSessionAgentRoleId(sessionId, request.agentRoleId).bind()
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

        // POST /api/v1/sessions/{sessionId}/clone - Clone a session with all its data
        post<SessionResource.ById.Clone> { resource ->
            val sessionId = resource.parent.sessionId
            val userId = call.getUserId()
            val request = call.receive<CloneSessionRequest>()
            val result = either {
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.READ)
                withError({ e: CloneSessionError -> e.toApiError() }) {
                    sessionService.cloneSession(sessionId, request.name).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        // WebSocket /api/v1/sessions/{sessionId}/messages - Process a new message for a session
        webSocket<SessionResource.ById.Messages>(protocol = CommonWebSocketProtocols.CHATBOT_AUTH) { resource ->
            val sessionId = resource.parent.sessionId
            val userId = call.getUserId()
            sessionMessagesWebSocketHandler.handle(
                socket = this,
                userId = userId,
                sessionId = sessionId
            )
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
