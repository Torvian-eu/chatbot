package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.models.api.mcp.GenerateServerIdResponse
import eu.torvian.chatbot.common.models.api.mcp.ServerIdsResponse
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.DeleteServerError
import eu.torvian.chatbot.server.service.core.error.mcp.ValidateOwnershipError
import eu.torvian.chatbot.server.service.core.error.mcp.toApiError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route

/**
 * Configures routes related to Local MCP Server ID Management (/api/v1/mcp-servers)
 * using Ktor Resources.
 *
 * This function sets up the following endpoints:
 * - POST /api/v1/mcp-servers/generate-id - Generate new server ID
 * - GET /api/v1/mcp-servers/ids - List all server IDs for user
 * - DELETE /api/v1/mcp-servers/{id} - Delete server ID
 *
 * Note: The server only manages ID generation and ownership tracking.
 * Full MCP server configurations are stored client-side.
 *
 * @param localMCPServerService The service handling Local MCP Server business logic
 */
fun Route.configureLocalMCPServerRoutes(
    localMCPServerService: LocalMCPServerService,
) {
    authenticate(AuthSchemes.USER_JWT) {
        // POST /api/v1/mcp-servers/generate-id - Generate new server ID
        post<LocalMCPServerResource.GenerateId> {
            val userId = call.getUserId()
            val serverId = localMCPServerService.generateServerId(userId)
            call.respond(HttpStatusCode.Created, GenerateServerIdResponse(id = serverId, userId = userId))
        }

        // GET /api/v1/mcp-servers/ids - List all server IDs for user
        get<LocalMCPServerResource.Ids> {
            val userId = call.getUserId()
            val serverIds = localMCPServerService.getServerIdsByUserId(userId)
            call.respond(ServerIdsResponse(ids = serverIds, userId = userId))
        }

        // DELETE /api/v1/mcp-servers/{id} - Delete server ID
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
    }
}

