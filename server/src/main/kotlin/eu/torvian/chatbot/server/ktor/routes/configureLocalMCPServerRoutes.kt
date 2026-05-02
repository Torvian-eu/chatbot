package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerDraftConnectionRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.ktor.auth.getWorkerId
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerServiceError
import eu.torvian.chatbot.server.service.core.error.mcp.toApiError
import eu.torvian.chatbot.server.worker.mcp.configsync.LocalMCPServerConfigSyncService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeControlError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeControlService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.toApiError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.routing.Route

/**
 * Configures routes related to Local MCP Server management (/api/v1/local-mcp-servers)
 * using Ktor Resources.
 *
 * User JWT routes expose full CRUD for server-owned Local MCP configuration.
 * Worker JWT routes expose read-only retrieval for worker-assigned servers.
 *
 * @param localMCPServerService The service handling Local MCP Server business logic
 * @param localMCPRuntimeControlService The service handling runtime control operations
 * @param localMCPServerConfigSyncService The service handling best-effort worker config synchronization
 */
fun Route.configureLocalMCPServerRoutes(
    localMCPServerService: LocalMCPServerService,
    localMCPRuntimeControlService: LocalMCPRuntimeControlService,
    localMCPServerConfigSyncService: LocalMCPServerConfigSyncService,
) {
    authenticate(AuthSchemes.USER_JWT) {
        post<LocalMCPServerResource> {
            val userId = call.getUserId()
            val request = call.receive<CreateLocalMCPServerRequest>()
            val result = either {
                withError({ error: LocalMCPServerServiceError -> error.toApiError() }) {
                    val server = localMCPServerService.createServer(userId, request).bind()
                    localMCPServerConfigSyncService.syncCreated(server)
                    server
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        get<LocalMCPServerResource> {
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPServerServiceError -> error.toApiError() }) {
                    localMCPServerService.getServersByUserId(userId).bind()
                }
            }
            call.respondEither(result)
        }

        get<LocalMCPServerResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPServerServiceError -> error.toApiError() }) {
                    localMCPServerService.getServerById(userId, resource.id).bind()
                }
            }
            call.respondEither(result)
        }

        get<LocalMCPServerResource.RuntimeStatuses> {
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPRuntimeControlError -> error.toApiError() }) {
                    localMCPRuntimeControlService.listRuntimeStatuses(userId).bind()
                }
            }
            call.respondEither(result)
        }

        get<LocalMCPServerResource.ById.RuntimeStatus> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPRuntimeControlError -> error.toApiError() }) {
                    localMCPRuntimeControlService.getRuntimeStatus(userId, resource.parent.id).bind()
                }
            }
            call.respondEither(result)
        }

        put<LocalMCPServerResource.ById> { resource ->
            val userId = call.getUserId()
            val request = call.receive<UpdateLocalMCPServerRequest>()
            val result = either {
                withError({ error: LocalMCPServerServiceError -> error.toApiError() }) {
                    val oldServer = localMCPServerService.getServerById(userId, resource.id).bind()
                    val updatedServer = localMCPServerService.updateServer(userId, resource.id, request).bind()
                    localMCPServerConfigSyncService.syncUpdated(
                        previousWorkerId = oldServer.workerId,
                        server = updatedServer
                    )
                    updatedServer
                }
            }
            call.respondEither(result)
        }

        delete<LocalMCPServerResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPServerServiceError -> error.toApiError() }) {
                    val server = localMCPServerService.getServerById(userId, resource.id).bind()
                    localMCPServerService.deleteServer(userId, resource.id).bind()
                    localMCPServerConfigSyncService.syncDeleted(workerId = server.workerId, serverId = server.id)
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        post<LocalMCPServerResource.ById.Start> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPRuntimeControlError -> error.toApiError() }) {
                    localMCPRuntimeControlService.startServer(userId, resource.parent.id).bind()
                }
            }
            call.respondEither(result)
        }

        post<LocalMCPServerResource.ById.Stop> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPRuntimeControlError -> error.toApiError() }) {
                    localMCPRuntimeControlService.stopServer(userId, resource.parent.id).bind()
                }
            }
            call.respondEither(result)
        }

        post<LocalMCPServerResource.ById.TestConnection> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPRuntimeControlError -> error.toApiError() }) {
                    localMCPRuntimeControlService.testConnection(userId, resource.parent.id).bind()
                }
            }
            call.respondEither(result)
        }

        post<LocalMCPServerResource.TestDraftConnection> {
            val userId = call.getUserId()
            val request = call.receive<TestLocalMCPServerDraftConnectionRequest>()
            val result = either {
                withError({ error: LocalMCPRuntimeControlError -> error.toApiError() }) {
                    localMCPRuntimeControlService.testDraftConnection(userId, request).bind()
                }
            }
            call.respondEither(result)
        }

        post<LocalMCPServerResource.ById.RefreshTools> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPRuntimeControlError -> error.toApiError() }) {
                    localMCPRuntimeControlService.refreshTools(userId, resource.parent.id).bind()
                }
            }
            call.respondEither(result)
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
            call.respondEither(result)
        }
    }
}

