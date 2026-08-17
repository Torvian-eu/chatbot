package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.OperatorToolResource
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.OperatorToolDefinitionService
import eu.torvian.chatbot.server.service.core.error.operator.ResetOperatorToolsError
import eu.torvian.chatbot.server.service.core.error.operator.UpdateOperatorToolError
import eu.torvian.chatbot.server.service.core.error.operator.toApiError
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * Configures routes related to Operator Tool Management (/api/v1/operator-tools).
 *
 * This function sets up the following endpoints:
 * - GET /api/v1/operator-tools - List the authenticated user's operator tools
 * - PUT /api/v1/operator-tools/{toolId} - Update an operator tool definition (full body)
 * - POST /api/v1/operator-tools/reset - Reset the user's operator tools to catalog defaults
 *
 * All endpoints require user JWT authentication. Ownership is enforced at the
 * service layer: a user can only view or modify their own operator tool instances.
 *
 * @param operatorToolDefinitionService The service handling operator tool business logic
 *                                      and ownership validation.
 */
fun Route.configureOperatorToolRoutes(
    operatorToolDefinitionService: OperatorToolDefinitionService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/operator-tools - List the authenticated user's operator tools
        get<OperatorToolResource> { _ ->
            val userId = call.getUserId()
            call.respond(operatorToolDefinitionService.getOperatorToolsForUser(userId))
        }

        // PUT /api/v1/operator-tools/{toolId} - Update an operator tool definition
        put<OperatorToolResource.ById> { resource ->
            val userId = call.getUserId()
            val toolId = resource.toolId
            val tool = call.receive<OperatorToolDefinition>()

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
                withError({ e: UpdateOperatorToolError -> e.toApiError() }) {
                    operatorToolDefinitionService.updateOperatorTool(userId, tool).bind()
                }
            }
            call.respondEither(result)
        }

        // POST /api/v1/operator-tools/reset - Reset the user's operator tools to catalog defaults
        post<OperatorToolResource.Reset> { _ ->
            val userId = call.getUserId()

            val result = either {
                withError({ e: ResetOperatorToolsError -> e.toApiError() }) {
                    operatorToolDefinitionService.resetOperatorToolsToDefaults(userId).bind()
                }
            }
            call.respondEither(result)
        }
    }
}
