package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.LocalMCPToolResource
import eu.torvian.chatbot.common.models.api.mcp.*
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.LocalMCPToolDefinitionService
import eu.torvian.chatbot.server.service.core.error.mcp.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlin.to

/**
 * Configures routes related to Local MCP Tool Management (/api/v1/local-mcp-tools)
 * using Ktor Resources.
 *
 * This function sets up the following endpoints:
 * - GET /api/v1/local-mcp-tools - Get all MCP tools for the current user
 * - POST /api/v1/local-mcp-tools/batch - Batch create MCP tools for a server
 * - POST /api/v1/local-mcp-tools/refresh - Refresh MCP tools (differential update)
 * - GET /api/v1/local-mcp-tools/server/{serverId} - Get all tools for a server
 * - GET /api/v1/local-mcp-tools/{toolId} - Get a single MCP tool by ID
 * - PUT /api/v1/local-mcp-tools/{toolId} - Update an MCP tool
 * - DELETE /api/v1/local-mcp-tools/server/{serverId} - Delete all tools for a server
 *
 * @param localMCPToolDefinitionService The service handling MCP tool business logic
 * @param localMCPServerService The service for validating server ownership
 */
fun Route.configureLocalMCPToolRoutes(
    localMCPToolDefinitionService: LocalMCPToolDefinitionService,
    localMCPServerService: LocalMCPServerService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/local-mcp-tools - Get all MCP tools for the current user
        get<LocalMCPToolResource> {
            val userId = call.getUserId()
            val tools = localMCPToolDefinitionService.getMCPToolsForUser(userId)
            call.respond(HttpStatusCode.OK, tools)
        }

        // POST /api/v1/local-mcp-tools/batch - Batch create MCP tools
        post<LocalMCPToolResource.Batch> {
            val userId = call.getUserId()
            val request = call.receive<CreateMCPToolsRequest>()
            val serverId = request.serverId

            val result = either {
                // Validate that the user owns the server
                withError({ e: ValidateOwnershipError -> e.toApiError() }) {
                    localMCPServerService.validateOwnership(userId, serverId).bind()
                }

                // Create the tools
                withError({ e: CreateMCPToolsError -> e.toApiError() }) {
                    val createdTools = localMCPToolDefinitionService.createMCPTools(
                        serverId = serverId,
                        tools = request.tools
                    ).bind()
                    CreateMCPToolsResponse(tools = createdTools)
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        // PUT /api/v1/local-mcp-tools/batch - Batch update MCP tools
        put<LocalMCPToolResource.Batch> {
            val userId = call.getUserId()
            val request = call.receive<BatchUpdateMCPToolsRequest>()
            val serverId = request.serverId

            val result = either {
                // Validate that the user owns the server
                withError({ e: ValidateOwnershipError -> e.toApiError() }) {
                    localMCPServerService.validateOwnership(userId, serverId).bind()
                }

                // Batch update the tools
                withError({ e: BatchUpdateMCPToolsError -> e.toApiError() }) {
                    val updatedTools = localMCPToolDefinitionService.batchUpdateMCPTools(
                        serverId = serverId,
                        toolDefinitions = request.toolDefinitions
                    ).bind()
                    BatchUpdateMCPToolsResponse(tools = updatedTools)
                }
            }
            call.respondEither(result)
        }

        // POST /api/v1/local-mcp-tools/refresh - Refresh MCP tools (differential update)
        post<LocalMCPToolResource.Refresh> {
            val userId = call.getUserId()
            val request = call.receive<RefreshMCPToolsRequest>()
            val serverId = request.serverId

            val result = either {
                // Validate that the user owns the server
                withError({ e: ValidateOwnershipError -> e.toApiError() }) {
                    localMCPServerService.validateOwnership(userId, serverId).bind()
                }

                // Refresh the tools
                withError({ e: RefreshMCPToolsError -> e.toApiError() }) {
                    val refreshResult = localMCPToolDefinitionService.refreshMCPTools(
                        serverId = serverId,
                        currentTools = request.currentTools
                    ).bind()
                    RefreshMCPToolsResponse(
                        addedTools = refreshResult.addedTools,
                        updatedTools = refreshResult.updatedTools,
                        deletedTools = refreshResult.deletedTools
                    )
                }
            }
            call.respondEither(result)
        }

        // GET /api/v1/local-mcp-tools/server/{serverId} - Get all tools for a server
        get<LocalMCPToolResource.ByServerId> { resource ->
            val userId = call.getUserId()
            val serverId = resource.serverId

            val result = either {
                // Validate that the user owns the server
                withError({ e: ValidateOwnershipError -> e.toApiError() }) {
                    localMCPServerService.validateOwnership(userId, serverId).bind()
                }

                // Get the tools
                withError({ e: GetMCPToolsByServerIdError -> e.toApiError() }) {
                    localMCPToolDefinitionService.getMCPToolsByServerId(serverId).bind()
                }
            }
            call.respondEither(result)
        }

        // GET /api/v1/local-mcp-tools/{toolId} - Get a single MCP tool by ID
        get<LocalMCPToolResource.ById> { resource ->
            val userId = call.getUserId()
            val toolId = resource.toolId

            val result = either {
                // Get the tool
                val tool = withError({ e: GetMCPToolByIdError -> e.toApiError() }) {
                    localMCPToolDefinitionService.getMCPToolById(toolId).bind()
                }

                // Validate that the user owns the server
                withError({ e: ValidateOwnershipError -> e.toApiError() }) {
                    localMCPServerService.validateOwnership(userId, tool.serverId).bind()
                }

                tool
            }
            call.respondEither(result)
        }

        // PUT /api/v1/local-mcp-tools/{toolId} - Update an MCP tool
        put<LocalMCPToolResource.ById> { resource ->
            val userId = call.getUserId()
            val toolId = resource.toolId
            val tool = call.receive<LocalMCPToolDefinition>()

            // Validate that the tool ID in the path matches the body
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
                // Validate that the user owns the server
                withError({ e: ValidateOwnershipError -> e.toApiError() }) {
                    localMCPServerService.validateOwnership(userId, tool.serverId).bind()
                }

                // Update the tool
                withError({ e: UpdateMCPToolError -> e.toApiError() }) {
                    localMCPToolDefinitionService.updateMCPTool(tool).bind()
                }
            }
            call.respondEither(result)
        }

        // DELETE /api/v1/local-mcp-tools/server/{serverId} - Delete all tools for a server
        delete<LocalMCPToolResource.ByServerId> { resource ->
            val userId = call.getUserId()
            val serverId = resource.serverId

            val result = either {
                // Validate that the user owns the server
                withError({ e: ValidateOwnershipError -> e.toApiError() }) {
                    localMCPServerService.validateOwnership(userId, serverId).bind()
                }

                // Delete the tools
                withError({ e: DeleteMCPToolsForServerError -> e.toApiError() }) {
                    val count = localMCPToolDefinitionService.deleteMCPToolsForServer(serverId).bind()
                    DeleteMCPToolsResponse(count = count)
                }
            }
            call.respondEither(result)
        }
    }
}

