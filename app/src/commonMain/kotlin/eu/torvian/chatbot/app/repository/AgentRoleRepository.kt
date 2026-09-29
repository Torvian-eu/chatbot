package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for user-defined agent roles with reactive data streams.
 *
 * This repository is the single source of truth for the current user's agent roles. It exposes
 * [roles] as a reactive [StateFlow] and keeps that stream in sync after every CRUD operation,
 * following the same pattern as [RoleRepository].
 */
interface AgentRoleRepository {

    /**
     * Reactive stream of all agent roles owned by the current user.
     *
     * This StateFlow provides real-time updates whenever role data changes, allowing ViewModels
     * and the chat top bar to react without manual refresh operations.
     *
     * @return StateFlow containing the current state of the role list wrapped in [DataState].
     */
    val roles: StateFlow<DataState<RepositoryError, List<AgentRoleDto>>>

    /**
     * Loads all agent roles from the server and updates [roles].
     *
     * This operation fetches the latest role data from the backend and updates the reactive stream.
     * If a load is already in progress, the call returns immediately without starting a duplicate
     * request.
     *
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun loadRoles(): Either<RepositoryError, Unit>

    /**
     * Loads the resolved detail of a single agent role and upserts it into [roles].
     *
     * The server resolves instruction text (notably the `spawnable_agents` instruction) on every read,
     * so this is used to refresh a role after edits made elsewhere.
     *
     * @param roleId The unique identifier of the role to load.
     * @return [Either.Right] with the loaded [AgentRoleDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun loadRoleDetails(roleId: Long): Either<RepositoryError, AgentRoleDto>

    /**
     * Creates a new agent role from a full configuration request.
     *
     * Validation stays server-side; this repository only maps API errors to [RepositoryError].
     * After successful creation the new role is appended to [roles].
     *
     * @param request The full configuration of the role to create.
     * @return [Either.Right] with the created [AgentRoleDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun createRole(request: CreateAgentRoleRequest): Either<RepositoryError, AgentRoleDto>

    /**
     * Replaces the configuration of an existing agent role.
     *
     * After successful update the updated role replaces the previous entry in [roles].
     *
     * @param roleId The unique identifier of the role to update.
     * @param request The replacement configuration.
     * @return [Either.Right] with the updated [AgentRoleDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun updateRole(roleId: Long, request: UpdateAgentRoleRequest): Either<RepositoryError, AgentRoleDto>

    /**
     * Sets the disabled state of a role for the current user (per-user, idempotent).
     *
     * After a successful toggle the updated role replaces the previous entry in [roles] (mirroring
     * the [updateRole] refresh pattern), so the chat selector and the settings tab react without a
     * manual reload. The settings tab reads the unfiltered stream and therefore keeps disabled roles
     * visible and re-enableable; the chat derivation filters them out on top of this same stream.
     *
     * @param roleId The unique identifier of the role to toggle.
     * @param disabled Desired state: `true` disables the role for the current user, `false` re-enables it.
     * @return [Either.Right] with the updated [AgentRoleDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun setRoleDisabled(roleId: Long, disabled: Boolean): Either<RepositoryError, AgentRoleDto>

    /**
     * Links an instruction to a role, appending it as the last element of that role's list.
     *
     * The link is a role mutation: the returned role (the server's echo of the new list) replaces the
     * previous entry in [roles], so any screen reading the role sees the appended instruction. The
     * instruction library's own `linkedRoleIds` changes too, and is refreshed by the caller that shows
     * usage rather than here, so this repository keeps a single refresh direction.
     *
     * @param roleId The unique identifier of the role to link to.
     * @param instructionId The unique identifier of the instruction to link.
     * @return [Either.Right] with the updated [AgentRoleDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun assignInstruction(roleId: Long, instructionId: Long): Either<RepositoryError, AgentRoleDto>

    /**
     * Removes an instruction's link from a role.
     *
     * Only the link is removed: the instruction row survives as a library entry. The returned role
     * replaces the previous entry in [roles], mirroring [assignInstruction].
     *
     * @param roleId The unique identifier of the role to unlink from.
     * @param instructionId The unique identifier of the instruction to unlink.
     * @return [Either.Right] with the updated [AgentRoleDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun unassignInstruction(roleId: Long, instructionId: Long): Either<RepositoryError, AgentRoleDto>

    /**
     * Deletes an agent role and removes it from [roles].
     *
     * Sessions referencing the role are unassigned server-side via `SET NULL`; the next session
     * reload re-derives `currentAgentRole = null` on the client.
     *
     * @param roleId The unique identifier of the role to delete.
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun deleteRole(roleId: Long): Either<RepositoryError, Unit>
}
