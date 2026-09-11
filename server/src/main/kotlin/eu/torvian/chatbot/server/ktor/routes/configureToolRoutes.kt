package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.resources.ToolResource
import eu.torvian.chatbot.common.models.api.tool.SetToolApprovalPreferenceRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.tool.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route

/**
 * Configures routes related to Tool Management (/api/v1/tools)
 * using Ktor Resources.
 *
 * A session's effective tools are resolved from its agent role; there are no
 * session-scoped tool endpoints.
 *
 * This function sets up the following endpoints:
 * - GET /api/v1/tools - List all tools accessible to the current user
 * - GET /api/v1/tools/{toolId} - Get tool details
 *
 * @param toolService The service handling tool business logic
 */
fun Route.configureToolRoutes(
    toolService: ToolService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/tools - List all tools accessible to the current user
        get<ToolResource> {
            val userId = call.getUserId()
            // Returns all global tools plus user-specific MCP tools
            call.respond(toolService.getToolsForUser(userId))
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

        // GET /api/v1/tools/approval-preferences - Get all approval preferences for current user
        get<ToolResource.ApprovalPreferences> {
            val userId = call.getUserId()
            call.respond(toolService.getAllApprovalPreferencesForUser(userId))
        }

        // PUT /api/v1/tools/approval-preferences - Set approval preference for a tool
        put<ToolResource.ApprovalPreferences> {
            val userId = call.getUserId()
            val request = call.receive<SetToolApprovalPreferenceRequest>()

            val result = either {
                withError({ e: SetToolApprovalPreferenceError -> e.toApiError() }) {
                    toolService.setToolApprovalPreference(
                        userId = userId,
                        toolDefinitionId = request.toolDefinitionId,
                        autoApprove = request.autoApprove,
                        conditions = request.conditions,
                        denialReason = request.denialReason
                    ).bind()
                }
            }
            call.respondEither(result)
        }

        // GET /api/v1/tools/approval-preferences/{toolId} - Get approval preference for a specific tool
        get<ToolResource.ApprovalPreferences.ByToolId> { resource ->
            val userId = call.getUserId()
            val toolId = resource.toolId

            val result = either {
                withError({ e: GetToolApprovalPreferenceError -> e.toApiError() }) {
                    toolService.getToolApprovalPreference(userId, toolId).bind()
                }
            }
            call.respondEither(result)
        }

        // DELETE /api/v1/tools/approval-preferences/{toolId} - Delete approval preference
        delete<ToolResource.ApprovalPreferences.ByToolId> { resource ->
            val userId = call.getUserId()
            val toolId = resource.toolId

            val result = either {
                withError({ e: DeleteToolApprovalPreferenceError -> e.toApiError() }) {
                    toolService.deleteToolApprovalPreference(userId, toolId).bind()
                }
            }
            call.respondEither(result)
        }
    }
}

