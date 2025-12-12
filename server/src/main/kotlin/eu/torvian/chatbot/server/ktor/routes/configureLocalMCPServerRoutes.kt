package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.models.api.mcp.CreateServerRequest
import eu.torvian.chatbot.common.models.api.mcp.CreateServerResponse
import eu.torvian.chatbot.common.models.api.mcp.SetServerEnabledRequest
import eu.torvian.chatbot.common.models.api.mcp.ServerIdsResponse
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.DeleteServerError
import eu.torvian.chatbot.server.service.core.error.mcp.ValidateOwnershipError
import eu.torvian.chatbot.server.service.core.error.mcp.toApiError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route

/**
 * Configures routes related to Local MCP Server management (/api/v1/local-mcp-servers)
 * using Ktor Resources.
 *
 * This function sets up the following endpoints:
 * - POST /api/v1/local-mcp-servers - Create new server
 * - GET /api/v1/local-mcp-servers/ids - List all server IDs for user
 * - DELETE /api/v1/local-mcp-servers/{id} - Delete server
 * - PUT /api/v1/local-mcp-servers/{id}/enabled - Update enabled state
 *
 * Note: The server manages ID generation, ownership tracking, and enabled state.
 * Full MCP server configurations are stored client-side. The client drives all
 * creation and updates via endpoints.
 *
 * @param localMCPServerService The service handling Local MCP Server business logic
 */
fun Route.configureLocalMCPServerRoutes(
    localMCPServerService: LocalMCPServerService,
) {
    authenticate(AuthSchemes.USER_JWT) {
        // POST /api/v1/local-mcp-servers - Create new server
        post<LocalMCPServerResource.Create> {
            val userId = call.getUserId()
            val request = call.receive<CreateServerRequest>()
            val serverId = localMCPServerService.createServer(userId, request.isEnabled)
            call.respond(HttpStatusCode.Created, CreateServerResponse(id = serverId, userId = userId, isEnabled = request.isEnabled))
        }

        // GET /api/v1/local-mcp-servers/ids - List all server IDs for user
        get<LocalMCPServerResource.Ids> {
            val userId = call.getUserId()
            val serverIds = localMCPServerService.getServerIdsByUserId(userId)
            call.respond(ServerIdsResponse(ids = serverIds, userId = userId))
        }

        // DELETE /api/v1/local-mcp-servers/{id} - Delete server
        delete<LocalMCPServerResource.ById> { resource ->
            val userId = call.getUserId()
            val serverId = resource.id

            val result = either {
                // Validate ownership before deletion
                withError({ e: ValidateOwnershipError -> e.toApiError() }) {
                    localMCPServerService.validateOwnership(userId, serverId).bind()
                }

                withError({ e: DeleteServerError -> e.toApiError() }) {
                    localMCPServerService.deleteServer(serverId).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        // PUT /api/v1/local-mcp-servers/{id}/enabled - Update enabled state
        put<LocalMCPServerResource.ById.SetEnabled> { resource ->
            val userId = call.getUserId()
            val serverId = resource.parent.id
            val request = call.receive<SetServerEnabledRequest>()

            val result = either {
                // Validate ownership before update
                withError({ e: ValidateOwnershipError -> e.toApiError() }) {
                    localMCPServerService.validateOwnership(userId, serverId).bind()
                }

                withError({ e: DeleteServerError -> e.toApiError() }) {
                    localMCPServerService.setServerEnabled(serverId, request.isEnabled).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }
    }
}

