package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.DeleteAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError

/**
 * Service interface for managing user-defined agent roles.
 *
 * Agent roles are personal configuration in this stage: every operation is scoped to the requesting
 * user, and the service verifies that the user owns the role before returning or mutating it. The
 * returned [AgentRoleDto]s always carry resolved instruction messages (see the server-side
 * `AgentInstruction.loadMessage()` resolution).
 */
interface AgentRoleService {

    /**
     * Retrieves all agent roles owned by the user.
     *
     * @param userId The ID of the user whose roles to retrieve.
     * @return List of [AgentRoleDto] owned by the user; empty list if the user owns no roles.
     */
    suspend fun getAllRolesForUser(userId: Long): List<AgentRoleDto>

    /**
     * Retrieves a single agent role by ID, verifying ownership.
     *
     * @param userId The ID of the requesting user.
     * @param roleId The ID of the role to retrieve.
     * @return Either [AgentRoleError.NotFound] if the role does not exist or is not owned by the user,
     *         or the [AgentRoleDto] with resolved instructions.
     */
    suspend fun getRoleById(userId: Long, roleId: Long): Either<AgentRoleError.NotFound, AgentRoleDto>

    /**
     * Retrieves a single agent role by name, verifying ownership.
     *
     * @param userId The ID of the requesting user.
     * @param name The machine-readable name of the role to retrieve.
     * @return Either [AgentRoleError.NotFoundByName] if the role does not exist or is not owned by the
     *         user, or the [AgentRoleDto] with resolved instructions.
     */
    suspend fun getRoleByName(userId: Long, name: String): Either<AgentRoleError.NotFoundByName, AgentRoleDto>

    /**
     * Loads a single agent role by ID as the server domain type, without ownership scoping.
     *
     * Intended for server-internal flows (e.g. turn preparation) where the role reference came from an
     * already-authorized session, so re-checking ownership is unnecessary. The returned role carries
     * domain [AgentInstruction] objects whose messages are resolved lazily via
     * `AgentInstruction.loadMessage()`.
     *
     * @param roleId The ID of the role to load.
     * @return Either [AgentRoleError.NotFound] if the role does not exist, or the domain [AgentRole].
     */
    suspend fun getAgentRoleById(roleId: Long): Either<AgentRoleError.NotFound, eu.torvian.chatbot.server.service.core.agent.AgentRole>

    /**
     * Creates a new agent role owned by the user.
     *
     * Validates the name, model/settings references (chat-capable and consistent), tool references,
     * and instruction-list rules before persisting. The newly created role is returned with resolved
     * instructions.
     *
     * @param userId The ID of the user who will own the role.
     * @param request The creation payload.
     * @return Either a [CreateAgentRoleError] or the newly created [AgentRoleDto].
     */
    suspend fun createRole(
        userId: Long,
        request: CreateAgentRoleRequest
    ): Either<CreateAgentRoleError, AgentRoleDto>

    /**
     * Updates an existing agent role owned by the user (a full configuration replacement).
     *
     * @param userId The ID of the requesting user.
     * @param roleId The ID of the role to update.
     * @param request The update payload.
     * @return Either an [UpdateAgentRoleError] or the updated [AgentRoleDto] with resolved instructions.
     */
    suspend fun updateRole(
        userId: Long,
        roleId: Long,
        request: UpdateAgentRoleRequest
    ): Either<UpdateAgentRoleError, AgentRoleDto>

    /**
     * Deletes an agent role owned by the user.
     *
     * Deleting a role is non-destructive for sessions: `chat_sessions.agent_role_id` and
     * `assistant_messages.agent_role_id` use `ON DELETE SET NULL`, so affected sessions become inert
     * until a role is re-selected.
     *
     * @param userId The ID of the requesting user.
     * @param roleId The ID of the role to delete.
     * @return Either a [DeleteAgentRoleError] or Unit on success.
     */
    suspend fun deleteRole(userId: Long, roleId: Long): Either<DeleteAgentRoleError, Unit>
}
