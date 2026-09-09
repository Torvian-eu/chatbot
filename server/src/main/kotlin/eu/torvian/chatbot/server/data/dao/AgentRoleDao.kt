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
 * Role names are unique **per user and project scope**, not globally: different users may reuse the
 * same name, and the same user may reuse a name in disjoint scopes (same-named roles conflict only
 * when they share the same scope — the same project id, or both unassociated). The DB cannot express
 * that constraint (ownership lives in the separate `agent_role_owners` table), so the uniqueness
 * checks live at the service layer; the DAO exposes [getRoleNameScopesForUser] and
 * scope-parameterized name lookups to support them.
 *
 * Project membership is a single nullable `project_id` column on the role row: a role belongs to at
 * most one project, and the membership is written together with the row in [insertRole]/[updateRole].
 */
interface AgentRoleDao {

    /**
     * The name-uniqueness scope of a single role: its id and its single project id.
     *
     * A null [projectId] means the role occupies the **unassociated scope** (it is offered only for
     * project-less sessions). Two same-named roles of the same user conflict iff their scopes are
     * identical: the same project id, or both null (both unassociated).
     *
     * @property roleId The role identifier.
     * @property projectId The single project id the role belongs to; null means unassociated.
     */
    data class AgentRoleNameScope(
        val roleId: Long,
        val projectId: Long?
    )

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
     * Retrieves an agent role by name, scoped to a single owner and a single project scope.
     *
     * Names are no longer unique per user alone: the same user may own same-named roles in disjoint
     * project scopes. The scope parameter disambiguates the lookup:
     *
     * - `projectId == null` resolves the role with **no** project association (the unassociated
     *   scope) — at most one such role exists per user per name;
     * - `projectId == P` resolves the role whose single project is P (at most one such role exists).
     *
     * @param userId ID of the owner user.
     * @param name The machine-readable name of the role.
     * @param projectId The project scope to resolve within: `null` for the unassociated scope, a
     *            project id for membership in that project.
     * @return Either [AgentRoleError.NotFoundByName] if no role of that name is owned by the user in
     *         the given scope, or the [AgentRoleEntity].
     */
    suspend fun getRoleByNameForUser(
        userId: Long,
        name: String,
        projectId: Long?
    ): Either<AgentRoleError.NotFoundByName, AgentRoleEntity>

    /**
     * Loads the requested roles that are owned by [userId], preserving the order of [roleIds].
     * Missing or foreign roles are omitted so callers can use the result as an ownership check.
     *
     * @param userId User whose ownership is required.
     * @param roleIds Role ids to resolve.
     * @return Owned role entities in requested order.
     */
    suspend fun getRolesByIdsForUser(userId: Long, roleIds: List<Long>): List<AgentRoleEntity>

    /**
     * Loads every role of [userId] carrying [name] together with its project scope.
     *
     * Used by the service layer to enforce the per-(user, scope) name-uniqueness rule: the caller
     * computes the candidate role's project scope and rejects the create/update when it equals any
     * other same-name role's scope (same project id, or both unassociated). Returns an empty list
     * when the user owns no role with that name.
     *
     * @param userId ID of the owner user.
     * @param name The machine-readable role name to look up.
     * @return The same-name roles of the user with their project scopes.
     */
    suspend fun getRoleNameScopesForUser(userId: Long, name: String): List<AgentRoleNameScope>

    /**
     * Creates a new agent role row.
     *
     * Name uniqueness is NOT enforced here (the column is not unique); the caller is responsible for
     * checking [getRoleNameScopesForUser] first. Technical persistence failures propagate as
     * exceptions. The membership column is written atomically with the row (a role belongs to at most
     * one project).
     *
     * @param name Machine-readable role name (unique per user and project scope; checked by the caller).
     * @param displayName Optional human-friendly display name.
     * @param description Free-form description.
     * @param modelId Optional identifier of the LLM model used by the role.
     * @param modelSettingsId Optional identifier of the settings profile used by the role.
     * @param instructionsJson Raw JSON array of the flat `AgentInstructionDto` list.
     * @param projectId The single project id the role belongs to, or null for an unassociated role.
     * @return The newly created [AgentRoleEntity].
     */
    suspend fun insertRole(
        name: String,
        displayName: String?,
        description: String,
        modelId: Long?,
        modelSettingsId: Long?,
        instructionsJson: String,
        projectId: Long?
    ): AgentRoleEntity

    /**
     * Updates an existing agent role row (a full replacement, including the `instructions_json`
     * column and the single `project_id` membership column). The role's tool set is a full
     * replacement too, but it is handled by [AgentRoleToolDao.replaceToolsForRole] separately.
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