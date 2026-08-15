package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest

/**
 * API client interface for user-defined agent role management.
 *
 * This interface defines the operations for CRUD against the `/api/v1/agent-roles` endpoints.
 * Roles are owned per-user; every endpoint is reachable with a `USER_JWT`. All methods return
 * [Either<ApiResourceError, T>] so callers handle failures explicitly.
 */
interface AgentRoleApi {

    /**
     * Retrieves all agent roles accessible to the current user.
     *
     * Corresponds to `GET /api/v1/agent-roles`.
     *
     * @return [Either.Right] containing the list of [AgentRoleDto] on success, or
     *         [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getAllRoles(): Either<ApiResourceError, List<AgentRoleDto>>

    /**
     * Retrieves a single agent role with server-resolved instruction text.
     *
     * Corresponds to `GET /api/v1/agent-roles/{roleId}`.
     *
     * @param roleId The unique identifier of the role to fetch.
     * @return [Either.Right] containing the requested [AgentRoleDto] on success, or
     *         [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getRoleById(roleId: Long): Either<ApiResourceError, AgentRoleDto>

    /**
     * Creates a new agent role with the given configuration.
     *
     * Corresponds to `POST /api/v1/agent-roles`.
     *
     * @param request The full configuration of the role to create.
     * @return [Either.Right] containing the newly created [AgentRoleDto] on success, or
     *         [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun createRole(request: CreateAgentRoleRequest): Either<ApiResourceError, AgentRoleDto>

    /**
     * Replaces the configuration of an existing agent role.
     *
     * Corresponds to `PUT /api/v1/agent-roles/{roleId}`. The update is a full replacement, not a patch.
     *
     * @param roleId The unique identifier of the role to update.
     * @param request The replacement configuration.
     * @return [Either.Right] containing the updated [AgentRoleDto] on success, or
     *         [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updateRole(roleId: Long, request: UpdateAgentRoleRequest): Either<ApiResourceError, AgentRoleDto>

    /**
     * Deletes an agent role. Sessions referencing it are unassigned via `SET NULL` on the server.
     *
     * Corresponds to `DELETE /api/v1/agent-roles/{roleId}`.
     *
     * @param roleId The unique identifier of the role to delete.
     * @return [Either.Right] with [Unit] on success, or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun deleteRole(roleId: Long): Either<ApiResourceError, Unit>
}
