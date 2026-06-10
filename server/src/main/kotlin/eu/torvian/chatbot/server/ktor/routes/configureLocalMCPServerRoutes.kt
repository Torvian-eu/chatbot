package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerDraftConnectionRequest
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.ktor.auth.getWorkerId
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerServiceError
import eu.torvian.chatbot.server.service.core.error.mcp.toApiError
import eu.torvian.chatbot.server.worker.mcp.configsync.LocalMCPServerConfigSyncError
import eu.torvian.chatbot.server.worker.mcp.configsync.LocalMCPServerConfigSyncService
import eu.torvian.chatbot.server.worker.mcp.configsync.toApiError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeControlError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeControlService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.toApiError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/**
 * Configures routes related to Local MCP Server management (/api/v1/local-mcp-servers)
 * using Ktor Resources.
 *
 * User JWT routes expose full CRUD for server-owned Local MCP configuration.
 * Worker JWT routes expose read-only retrieval for worker-assigned servers.
 *
 * @param localMCPServerService The service handling Local MCP Server business logic.
 * @param localMCPRuntimeControlService The service handling runtime control operations.
 * @param localMCPServerConfigSyncService The service orchestrating write persistence together with worker sync.
 * @param json JSON codec used to decode the exact raw request body after it has been captured for signature persistence.
 */
fun Route.configureLocalMCPServerRoutes(
    localMCPServerService: LocalMCPServerService,
    localMCPRuntimeControlService: LocalMCPRuntimeControlService,
    localMCPServerConfigSyncService: LocalMCPServerConfigSyncService,
    json: Json,
) {
    authenticate(AuthSchemes.USER_JWT) {
        post<LocalMCPServerResource> {
            val userId = call.getUserId()
            val result = either {
                val (request, signedRequest) = receiveDetachedSignedRequest<CreateLocalMCPServerRequest>(json).bind()
                withError({ error: LocalMCPServerConfigSyncError -> error.toApiError() }) {
                    localMCPServerConfigSyncService.createSignedServer(userId, request, signedRequest).bind()
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
            val result = either {
                val (request, signedRequest) = receiveDetachedSignedRequest<UpdateLocalMCPServerRequest>(json).bind()
                withError({ error: LocalMCPServerConfigSyncError -> error.toApiError() }) {
                    localMCPServerConfigSyncService.updateSignedServer(
                        userId = userId,
                        serverId = resource.id,
                        request = request,
                        signedRequest = signedRequest
                    ).bind()
                }
            }
            call.respondEither(result)
        }

        delete<LocalMCPServerResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: LocalMCPServerConfigSyncError -> error.toApiError() }) {
                    localMCPServerConfigSyncService.deleteServer(userId, resource.id).bind()
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
            val result = either {
                val (request, signedRequest) = receiveDetachedSignedRequest<TestLocalMCPServerDraftConnectionRequest>(json).bind()
                withError({ error: LocalMCPRuntimeControlError -> error.toApiError() }) {
                    localMCPRuntimeControlService.testDraftConnection(userId, request, signedRequest).bind()
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
                    localMCPServerService.getSignedServersByWorkerId(workerId).bind()
                }
            }
            call.respondEither(result)
        }
    }
}