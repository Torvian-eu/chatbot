package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.SessionToolsResource
import eu.torvian.chatbot.common.api.resources.ToolResource
import eu.torvian.chatbot.common.models.api.tool.CreateToolRequest
import eu.torvian.chatbot.common.models.api.tool.SetToolEnabledRequest
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.tool.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route

/**
 * Configures routes related to Tool Management (/api/v1/tools and /api/v1/sessions/{sessionId}/tools)
 * using Ktor Resources.
 *
 * This function sets up the following endpoints:
 * - GET /api/v1/tools - List all tools
 * - POST /api/v1/tools - Create new tool (admin only)
 * - GET /api/v1/tools/{toolId} - Get tool details
 * - PUT /api/v1/tools/{toolId} - Update tool (admin only)
 * - DELETE /api/v1/tools/{toolId} - Delete tool (admin only)
 * - GET /api/v1/sessions/{sessionId}/tools - Get enabled tools for session
 * - PUT /api/v1/sessions/{sessionId}/tools/{toolId} - Enable/disable tool for session
 *
 * @param toolService The service handling tool business logic
 * @param authorizationService The service handling authorization checks
 */
fun Route.configureToolRoutes(
    toolService: ToolService,
    authorizationService: AuthorizationService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/tools - List all tools
        get<ToolResource> {
            // All authenticated users can view available tools
            call.respond(toolService.getAllTools())
        }

        // POST /api/v1/tools - Create a new tool (admin only)
        post<ToolResource> {
            val userId = call.getUserId()
            val request = call.receive<CreateToolRequest>()

            val result = either {
                // Only users with MANAGE_TOOLS permission can create tools
                requireToolManagementAccess(authorizationService, userId)

                withError({ e: CreateToolError -> e.toApiError() }) {
                    toolService.createTool(
                        name = request.name,
                        description = request.description,
                        type = request.type,
                        config = request.config,
                        inputSchema = request.inputSchema,
                        outputSchema = request.outputSchema,
                        isEnabled = request.isEnabled
                    ).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        // GET /api/v1/tools/{toolId} - Get tool details
        get<ToolResource.ById> { resource ->
            val toolId = resource.toolId

            val result = either {
                withError({ e: GetToolError -> e.toApiError() }) {
                    toolService.getToolById(toolId).bind()
                }
            }
            call.respondEither(result)
        }

        // PUT /api/v1/tools/{toolId} - Update tool (admin only)
        put<ToolResource.ById> { resource ->
            val userId = call.getUserId()
            val toolId = resource.toolId
            val tool = call.receive<ToolDefinition>()

            if (tool.id != toolId) {
                val error = apiError(
                    apiCode = CommonApiErrorCodes.INVALID_ARGUMENT,
                    message = "Tool ID in path and body must match",
                    "pathId" to toolId.toString(),
                    "bodyId" to tool.id.toString()
                )
                return@put call.respond(HttpStatusCode.fromValue(error.statusCode), error)
            }

            val result = either {
                // Only users with MANAGE_TOOLS permission can update tools
                requireToolManagementAccess(authorizationService, userId)

                withError({ e: UpdateToolError -> e.toApiError() }) {
                    toolService.updateTool(tool).bind()
                }
            }
            call.respondEither(result)
        }

        // DELETE /api/v1/tools/{toolId} - Delete tool (admin only)
        delete<ToolResource.ById> { resource ->
            val userId = call.getUserId()
            val toolId = resource.toolId

            val result = either {
                // Only users with MANAGE_TOOLS permission can delete tools
                requireToolManagementAccess(authorizationService, userId)

                withError({ e: DeleteToolError -> e.toApiError() }) {
                    toolService.deleteTool(toolId).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        // GET /api/v1/sessions/{sessionId}/tools - Get enabled tools for session
        get<SessionToolsResource> { resource ->
            val userId = call.getUserId()
            val sessionId = resource.parent.sessionId

            val result = either {
                // Check that the user has read access to the session
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.READ)

                toolService.getEnabledToolsForSession(sessionId)
            }
            call.respondEither(result)
        }

        // PUT /api/v1/sessions/{sessionId}/tools/{toolId} - Enable/disable tool for session
        put<SessionToolsResource.ById> { resource ->
            val userId = call.getUserId()
            val sessionId = resource.parent.parent.sessionId
            val toolId = resource.toolId
            val request = call.receive<SetToolEnabledRequest>()

            val result = either {
                // Check that the user has write access to the session
                requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)

                withError({ e: SetToolEnabledError -> e.toApiError() }) {
                    toolService.setToolEnabledForSession(sessionId, toolId, request.enabled).bind()
                }
            }
            call.respondEither(result)
        }
    }
}

