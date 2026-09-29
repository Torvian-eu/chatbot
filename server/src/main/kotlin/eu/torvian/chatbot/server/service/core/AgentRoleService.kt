package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.service.core.agent.AgentRole
import eu.torvian.chatbot.server.service.core.agent.DeleteAgentRoleResult
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.AssignInstructionError
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.DeleteAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UnassignInstructionError
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
     * Names are no longer unique per user alone (the same user may own same-named roles in disjoint
     * project scopes), so the lookup is scope-parameterized: `projectId == null` resolves the
     * **unassociated scope** (a role with no project association) and `projectId == P` resolves a
     * member of project P. Per the per-(user, name, scope) uniqueness rule at most one role exists in
     * each scope, so the result stays unambiguous.
     *
     * @param userId The ID of the requesting user.
     * @param name The machine-readable name of the role to retrieve.
     * @param projectId The project scope to resolve within; `null` (the default) means the unassociated
     *            scope, a project id means membership in that project.
     * @return Either [AgentRoleError.NotFoundByName] if the role does not exist in that scope or is
     *         not owned by the user, or the [AgentRoleDto] with resolved instructions.
     */
    suspend fun getRoleByName(
        userId: Long,
        name: String,
        projectId: Long? = null
    ): Either<AgentRoleError.NotFoundByName, AgentRoleDto>

    /**
     * Loads a single agent role by ID as the server domain type, without ownership scoping.
     *
     * Intended for server-internal flows (e.g. turn preparation) where the role reference came from an
     * already-authorized session, so re-checking ownership is unnecessary. The returned role carries
     * domain `AgentInstruction` objects whose messages are resolved lazily via
     * `AgentInstruction.loadMessage()`, and the per-user `disabled` flag resolved for [userId] (the
     * caller has already bound [userId] to the session, so scoping only the flag — never the role row
     * lookup — keeps the per-user semantics correct for future shared roles).
     *
     * @param userId The requesting user whose per-user disabled state applies to the loaded role.
     * @param roleId The ID of the role to load.
     * @return Either [AgentRoleError.NotFound] if the role does not exist, or the domain [AgentRole].
     */
    suspend fun getAgentRoleById(userId: Long, roleId: Long): Either<AgentRoleError.NotFound, AgentRole>

    /**
     * Sets the disabled state of a role owned by [userId] for that same user, idempotently.
     *
     * The operation is ownership-checked (a foreign or nonexistent role collapses to
     * [AgentRoleError.NotFound], consistent with every other role op) and scoped per user: a row
     * `(user, role)` is inserted for `disabled = true` and deleted for `false`. The returned DTO
     * always carries the newly requested [AgentRoleDto.disabled] value.
     *
     * @param userId The requesting user; also the owner whose ownership is verified and the user the
     *            disabled state applies to.
     * @param roleId The ID of the role to toggle.
     * @param disabled Desired state: `true` disables the role for [userId], `false` re-enables it.
     * @return Either [AgentRoleError.NotFound] or the updated [AgentRoleDto] with resolved instructions.
     */
    suspend fun setRoleDisabled(userId: Long, roleId: Long, disabled: Boolean): Either<AgentRoleError.NotFound, AgentRoleDto>

    /**
     * Creates a new agent role owned by the user.
     *
     * Validates the name, model/settings references (chat-capable and consistent), tool references,
     * and instruction-list rules before persisting. The payload links exactly the referenced
     * instruction rows, in the given order; their content is authored through the instruction surfaces
     * and is never touched by a role write. The newly created role is returned with resolved
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
     * The instruction list is a full replacement too: the payload's ordered ids become the role's
     * links, and dropped entries lose only this role's link (their rows persist as library entries).
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
     * Links one instruction row to an agent role, appending it as the role's last element.
     *
     * The operation mutates the role's ordered instruction list, which is why it lives on this service
     * rather than on the instruction service; the reported [AgentRoleDto] is the role's state after the
     * write, so the caller receives the new order and every entry's linking roles. Both the role and the
     * row must be owned by [userId] (foreign and nonexistent ids collapse into the same not-found error
     * on each side), the pair must not be linked yet, and the resulting list must satisfy the per-role
     * instruction rules — a violation is rejected before any write.
     *
     * @param userId The ID of the requesting user, whose ownership of both sides is required.
     * @param roleId The ID of the role to link to.
     * @param instructionId The ID of the instruction row to link.
     * @return Either an [AssignInstructionError] or the updated [AgentRoleDto] with resolved
     *         instructions.
     */
    suspend fun assignInstruction(
        userId: Long,
        roleId: Long,
        instructionId: Long
    ): Either<AssignInstructionError, AgentRoleDto>

    /**
     * Removes one instruction row's link from an agent role.
     *
     * Only the link is removed: the instruction row survives as a library entry, so it stays available
     * for other roles and reappears under an unassigned listing when no role links it any more. The
     * affected role keeps its remaining instructions in unchanged relative order and its stored
     * positions stay contiguous. Both the role and the row must be owned by [userId], and the pair must
     * currently be linked.
     *
     * @param userId The ID of the requesting user, whose ownership of both sides is required.
     * @param roleId The ID of the role to unlink from.
     * @param instructionId The ID of the instruction row to unlink.
     * @return Either an [UnassignInstructionError] or the updated [AgentRoleDto] with resolved
     *         instructions.
     */
    suspend fun unassignInstruction(
        userId: Long,
        roleId: Long,
        instructionId: Long
    ): Either<UnassignInstructionError, AgentRoleDto>

    /**
     * Deletes an agent role owned by the user.
     *
     * The implementation enforces ownership in the same transaction as the delete and collapses a
     * foreign role (owned by another user) with a nonexistent role into
     * [DeleteAgentRoleError.NotFound] so the caller cannot distinguish "someone else's role" from
     * "no such role".
     *
     * Deleting is content-destructive for unreferenced instruction rows: every instruction row that
     * loses its last link through this deletion is removed together with its `instruction_owners`
     * row, in the same transaction as the role delete. Rows still linked to at least one other role,
     * and rows that were never linked, survive as library entries. The result reports both fates:
     * the ids of the removed rows and of the linked rows kept on other roles.
     *
     * Deleting a role is non-destructive for sessions: `chat_sessions.agent_role_id` and
     * `assistant_messages.agent_role_id` use `ON DELETE SET NULL`, so affected sessions become inert
     * until a role is re-selected.
     *
     * @param userId The ID of the requesting user.
     * @param roleId The ID of the role to delete.
     * @return Either a [DeleteAgentRoleError], or a [DeleteAgentRoleResult] naming the removed and
     *         the kept instruction rows.
     */
    suspend fun deleteRole(userId: Long, roleId: Long): Either<DeleteAgentRoleError, DeleteAgentRoleResult>
}
