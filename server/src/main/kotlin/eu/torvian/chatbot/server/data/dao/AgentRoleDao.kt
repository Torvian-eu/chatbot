package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.AgentRoleError
import eu.torvian.chatbot.server.data.entities.AgentRoleEntity

/**
 * Data Access Object for agent-role entities.
 *
 * The complex `instructions` value lives in a JSON column, so every operation is a single-row
 * read/write: the DAO receives the raw JSON string (or returns it verbatim) and the service layer
 * owns (de)serialization via the shared JSON codec. The role's tool ids are stored separately in the
 * `agent_role_tools` join table and are managed through [AgentRoleToolDao].
 *
 * Role names are unique **per user**, not globally: different users may reuse the same name. The DB
 * cannot express that constraint (ownership lives in the separate `agent_role_owners` table), so the
 * per-user uniqueness checks live at the service layer; the DAO exposes [roleNameExistsForUser] and
 * user-scoped name lookups to support them.
 */
interface AgentRoleDao {
    /**
     * Retrieves all agent roles in the system.
     *
     * @return List of all [AgentRoleEntity] objects; empty list if no roles exist.
     */
    suspend fun getAllRoles(): List<AgentRoleEntity>

    /**
     * Retrieves all agent roles owned by the given user, joined through the ownership table.
     *
     * @param userId ID of the owner user.
     * @return List of [AgentRoleEntity] owned by the user; empty list if the user owns no roles.
     */
    suspend fun getAllRolesForUser(userId: Long): List<AgentRoleEntity>

    /**
     * Retrieves an agent role by its unique ID.
     *
     * @param id The unique identifier of the role.
     * @return Either [AgentRoleError.NotFound] if not found, or the [AgentRoleEntity].
     */
    suspend fun getRoleById(id: Long): Either<AgentRoleError.NotFound, AgentRoleEntity>

    /**
     * Retrieves an agent role by name, scoped to a single owner.
     *
     * Names are unique per user, so a (user, name) pair identifies at most one role; this replaces the
     * old global name lookup, which became ambiguous once names could repeat across users.
     *
     * @param userId ID of the owner user.
     * @param name The machine-readable name of the role.
     * @return Either [AgentRoleError.NotFoundByName] if no role of that name is owned by the user, or
     *         the [AgentRoleEntity].
     */
    suspend fun getRoleByNameForUser(userId: Long, name: String): Either<AgentRoleError.NotFoundByName, AgentRoleEntity>

    /**
     * Whether the user already owns an agent role with the given name.
     *
     * Used by the service layer to enforce per-user name uniqueness.
     *
     * @param userId ID of the owner user.
     * @param name The machine-readable role name to check.
     * @return `true` if the user owns a role with that name, `false` otherwise.
     */
    suspend fun roleNameExistsForUser(userId: Long, name: String): Boolean

    /**
     * Creates a new agent role row.
     *
     * Name uniqueness is NOT enforced here (the column is not unique); the caller is responsible for
     * checking [roleNameExistsForUser] first. Technical persistence failures propagate as exceptions.
     *
     * @param name Machine-readable role name (unique per user; checked by the caller).
     * @param displayName Optional human-friendly display name.
     * @param description Free-form description.
     * @param modelId Optional identifier of the LLM model used by the role.
     * @param modelSettingsId Optional identifier of the settings profile used by the role.
     * @param instructionsJson Raw JSON array of the flat `AgentInstructionDto` list.
     * @return The newly created [AgentRoleEntity].
     */
    suspend fun insertRole(
        name: String,
        displayName: String?,
        description: String,
        modelId: Long?,
        modelSettingsId: Long?,
        instructionsJson: String
    ): AgentRoleEntity

    /**
     * Updates an existing agent role row (a full replacement, including the `instructions_json`
     * column). The role's tool set is a full replacement too, but it is handled by
     * [AgentRoleToolDao.replaceToolsForRole] separately.
     *
     * @param role The [AgentRoleEntity] with updated values. The ID must match an existing role.
     * @return Either [AgentRoleError.NotFound] if the role does not exist, or Unit on success.
     */
    suspend fun updateRole(role: AgentRoleEntity): Either<AgentRoleError.NotFound, Unit>

    /**
     * Deletes an agent role by ID.
     *
     * @param id The unique identifier of the role to delete.
     * @return Either [AgentRoleError.NotFound] if not found, or Unit on success.
     */
    suspend fun deleteRole(id: Long): Either<AgentRoleError.NotFound, Unit>
}
