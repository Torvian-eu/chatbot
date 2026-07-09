package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.resources.BuiltInToolResource
import eu.torvian.chatbot.common.models.api.tool.UpdateBuiltInToolRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.BuiltInToolDefinitionService
import eu.torvian.chatbot.server.service.core.error.builtin.GetBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.builtin.UpdateBuiltInToolError
import eu.torvian.chatbot.server.service.core.error.builtin.toApiError
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.routing.Route

/**
 * Configures routes related to Built-in Worker Tool Management (/api/v1/built-in-tools)
 * using Ktor Resources.
 *
 * This function sets up the following endpoints:
 * - GET /api/v1/built-in-tools/worker/{workerId} - List all built-in tools for a worker
 * - PUT /api/v1/built-in-tools/{toolId} - Update a built-in tool's enabled state
 *
 * All endpoints require user JWT authentication. Ownership is enforced at the
 * service layer: a user can only view or modify tools belonging to their own workers.
 *
 * @param builtInToolDefinitionService The service handling built-in tool business logic
 *                                     and ownership validation.
 */
fun Route.configureBuiltInToolRoutes(
    builtInToolDefinitionService: BuiltInToolDefinitionService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/built-in-tools/worker/{workerId} - List built-in tools for a worker
        get<BuiltInToolResource.ByWorkerId> { resource ->
            val userId = call.getUserId()
            val workerId = resource.workerId

            val result = either {
                withError({ e: GetBuiltInToolsError -> e.toApiError() }) {
                    builtInToolDefinitionService.getBuiltInToolsForWorker(userId, workerId).bind()
                }
            }
            call.respondEither(result)
        }

        // PUT /api/v1/built-in-tools/{toolId} - Update a built-in tool's enabled state
        put<BuiltInToolResource.ById> { resource ->
            val userId = call.getUserId()
            val toolId = resource.toolId
            val request = call.receive<UpdateBuiltInToolRequest>()

            val result = either {
                withError({ e: UpdateBuiltInToolError -> e.toApiError() }) {
                    builtInToolDefinitionService.updateBuiltInTool(userId, toolId, request.isEnabled).bind()
                }
            }
            call.respondEither(result)
        }
    }
}
