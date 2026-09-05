package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.resources.AgentRoleResource
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleDisabledRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.delete
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Configures routes related to user-defined Agent Roles (/api/v1/agent-roles) using Ktor Resources.
 *
 * Agent roles are personal configuration in this stage, so every operation is scoped to the
 * authenticated user and the [AgentRoleService] verifies ownership before returning or mutating a role.
 *
 * Available endpoints:
 * - GET /api/v1/agent-roles - List roles owned by the user
 * - POST /api/v1/agent-roles - Create a new role
 * - GET /api/v1/agent-roles/{roleId} - Get a specific role (with resolved instructions)
 * - PUT /api/v1/agent-roles/{roleId} - Update a specific role
 * - DELETE /api/v1/agent-roles/{roleId} - Delete a specific role
 * - PUT /api/v1/agent-roles/{roleId}/disabled - Set the per-user disabled state of a specific role
 *
 * @param agentRoleService Service backing the agent-role CRUD operations.
 * @param authorizationService Authorization service retained for parity with the other resource routes;
 *            ownership enforcement is delegated to [AgentRoleService].
 */
fun Route.configureAgentRoleRoutes(
    agentRoleService: AgentRoleService,
    authorizationService: AuthorizationService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/agent-roles - List all roles owned by the requesting user
        get<AgentRoleResource> {
            val userId = call.getUserId()
            call.respond(agentRoleService.getAllRolesForUser(userId))
        }

        // GET /api/v1/agent-roles/{roleId} - Get role by ID (ownership checked)
        get<AgentRoleResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ e: AgentRoleError -> e.toApiError() }) {
                    agentRoleService.getRoleById(userId, resource.roleId).bind()
                }
            }
            call.respondEither(result)
        }

        // POST /api/v1/agent-roles - Create a new role owned by the requesting user
        post<AgentRoleResource> {
            val userId = call.getUserId()
            val request = call.receive<CreateAgentRoleRequest>()

            val result = either {
                withError({ e: CreateAgentRoleError -> e.toApiError() }) {
                    agentRoleService.createRole(userId, request).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        // PUT /api/v1/agent-roles/{roleId} - Update role (ownership checked)
        put<AgentRoleResource.ById> { resource ->
            val userId = call.getUserId()
            val request = call.receive<UpdateAgentRoleRequest>()

            val result = either {
                withError({ e: UpdateAgentRoleError -> e.toApiError() }) {
                    agentRoleService.updateRole(userId, resource.roleId, request).bind()
                }
            }
            call.respondEither(result)
        }

        // DELETE /api/v1/agent-roles/{roleId} - Delete role (ownership checked)
        delete<AgentRoleResource.ById> { resource ->
            val userId = call.getUserId()

            val result = either {
                withError({ e: DeleteAgentRoleError -> e.toApiError() }) {
                    agentRoleService.deleteRole(userId, resource.roleId).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        // PUT /api/v1/agent-roles/{roleId}/disabled - Toggle the per-user disabled state (ownership checked)
        put<AgentRoleResource.ById.Disabled> { resource ->
            val userId = call.getUserId()
            val request = call.receive<UpdateAgentRoleDisabledRequest>()

            val result = either {
                withError({ e: AgentRoleError -> e.toApiError() }) {
                    agentRoleService.setRoleDisabled(userId, resource.parent.roleId, request.disabled).bind()
                }
            }
            call.respondEither(result)
        }
    }
}
