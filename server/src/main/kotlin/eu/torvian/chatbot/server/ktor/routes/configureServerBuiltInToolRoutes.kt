package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.ServerBuiltInToolResource
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolDefinitionService
import eu.torvian.chatbot.server.service.core.error.serverbuiltin.ResetServerBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.serverbuiltin.UpdateServerBuiltInToolError
import eu.torvian.chatbot.server.service.core.error.serverbuiltin.toApiError
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * Configures routes related to Server Built-In Tool Management (/api/v1/server-built-in-tools)
 * using Ktor Resources.
 *
 * This function sets up the following endpoints:
 * - GET /api/v1/server-built-in-tools - List the authenticated user's server built-in tools
 * - PUT /api/v1/server-built-in-tools/{toolId} - Update a server built-in tool definition (full body)
 * - POST /api/v1/server-built-in-tools/reset - Reset the user's server built-in tools to catalog defaults
 *
 * All endpoints require user JWT authentication. Ownership is enforced at the service layer: a user
 * can only view or modify their own server built-in tool instances.
 *
 * @param serverBuiltInToolDefinitionService The service handling server built-in tool business
 *                                           logic and ownership validation.
 */
fun Route.configureServerBuiltInToolRoutes(
    serverBuiltInToolDefinitionService: ServerBuiltInToolDefinitionService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/server-built-in-tools - List the authenticated user's server built-in tools
        get<ServerBuiltInToolResource> { _ ->
            val userId = call.getUserId()
            call.respond(serverBuiltInToolDefinitionService.getServerBuiltInToolsForUser(userId))
        }

        // PUT /api/v1/server-built-in-tools/{toolId} - Update a server built-in tool definition
        put<ServerBuiltInToolResource.ById> { resource ->
            val userId = call.getUserId()
            val toolId = resource.toolId
            val tool = call.receive<ServerBuiltInToolDefinition>()

            // The path id must match the body id to avoid ambiguous updates.
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
                withError({ e: UpdateServerBuiltInToolError -> e.toApiError() }) {
                    serverBuiltInToolDefinitionService.updateServerBuiltInTool(userId, tool).bind()
                }
            }
            call.respondEither(result)
        }

        // POST /api/v1/server-built-in-tools/reset - Reset the user's server built-in tools to
        // catalog defaults
        post<ServerBuiltInToolResource.Reset> { _ ->
            val userId = call.getUserId()

            val result = either {
                withError({ e: ResetServerBuiltInToolsError -> e.toApiError() }) {
                    serverBuiltInToolDefinitionService.resetServerBuiltInToolsToDefaults(userId).bind()
                }
            }
            call.respondEither(result)
        }
    }
}
