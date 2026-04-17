package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerListResponse
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerResponse
import eu.torvian.chatbot.common.models.api.mcp.ServerIdsResponse
import eu.torvian.chatbot.common.models.api.mcp.SetServerEnabledRequest
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.ktor.auth.getWorkerId
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerServiceError
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
 * User JWT routes expose full CRUD for server-owned Local MCP configuration.
 * Worker JWT routes expose read-only retrieval for worker-assigned servers.
 *
 * @param localMCPServerService The service handling Local MCP Server business logic
 */
fun Route.configureLocalMCPServerRoutes(
    localMCPServerService: LocalMCPServerService,
) {
    authenticate(AuthSchemes.USER_JWT) {
        post<LocalMCPServerResource> {
            val userId = call.getUserId()
            val request = call.receive<CreateLocalMCPServerRequest>()
            val result = either {
                withError({ error: LocalMCPServerServiceError -> error.toApiError() }) {
                    localMCPServerService.createServer(userId, request).bind()
                }
            }
            call.respondEither(result.map { LocalMCPServerResponse(it) }, HttpStatusCode.Created)
        }

        get<LocalMCPServerResource> {
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPServerServiceError -> error.toApiError() }) {
                    localMCPServerService.getServersByUserId(userId).bind()
                }
            }
            call.respondEither(result.map { LocalMCPServerListResponse(it) })
        }

        get<LocalMCPServerResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPServerServiceError -> error.toApiError() }) {
                    localMCPServerService.getServerById(userId, resource.id).bind()
                }
            }
            call.respondEither(result.map { LocalMCPServerResponse(it) })
        }

        put<LocalMCPServerResource.ById> { resource ->
            val userId = call.getUserId()
            val request = call.receive<UpdateLocalMCPServerRequest>()
            val result = either {
                withError({ error: LocalMCPServerServiceError -> error.toApiError() }) {
                    localMCPServerService.updateServer(userId, resource.id, request).bind()
                }
            }
            call.respondEither(result.map { LocalMCPServerResponse(it) })
        }

        delete<LocalMCPServerResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPServerServiceError -> error.toApiError() }) {
                    localMCPServerService.deleteServer(userId, resource.id).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        // Legacy compatibility endpoint: return projected IDs from full records.
        get<LocalMCPServerResource.Ids> {
            val userId = call.getUserId()
            val serverIds = localMCPServerService.getServerIdsByUserId(userId)
            call.respond(ServerIdsResponse(ids = serverIds, userId = userId))
        }

        // Legacy compatibility endpoint: partial enabled-state update.
        put<LocalMCPServerResource.ById.SetEnabled> { resource ->
            val serverId = resource.parent.id
            val request = call.receive<SetServerEnabledRequest>()

            val result = either {
                withError({ error: LocalMCPServerServiceError -> error.toApiError() }) {
                    localMCPServerService.setServerEnabled(serverId, request.isEnabled).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }
    }

    authenticate(AuthSchemes.WORKER_JWT) {
        get<LocalMCPServerResource.Assigned> {
            val workerId = call.getWorkerId()
            val result = either {
                withError({ error: LocalMCPServerServiceError -> error.toApiError() }) {
                    localMCPServerService.getServersByWorkerId(workerId).bind()
                }
            }
            call.respondEither(result.map { LocalMCPServerListResponse(it) })
        }
    }
}

