package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.RoleError
import eu.torvian.chatbot.server.data.entities.RoleEntity

/**
 * Data Access Object for managing user roles.
 *
 * This DAO provides CRUD operations for roles, which are used to group
 * permissions and assign them to users for access control.
 */
interface RoleDao {
    /**
     * Retrieves all roles in the system.
     *
     * @return List of all [RoleEntity] objects; empty list if no roles exist.
     */
    suspend fun getAllRoles(): List<RoleEntity>

    /**
     * Retrieves a role by its unique ID.
     *
     * @param id The unique identifier of the role.
     * @return Either [RoleError.RoleNotFound] if not found, or the [RoleEntity].
     */
    suspend fun getRoleById(id: Long): Either<RoleError.RoleNotFound, RoleEntity>

    /**
     * Retrieves a role by its unique name.
     *
     * @param name The name of the role (e.g., "Admin", "StandardUser").
     * @return Either [RoleError.RoleNotFoundByName] if not found, or the [RoleEntity].
     */
    suspend fun getRoleByName(name: String): Either<RoleError.RoleNotFoundByName, RoleEntity>

    /**
     * Creates a new role.
     *
     * @param name Unique name for the role.
     * @param description Optional description of the role.
     * @return Either [RoleError.RoleNameAlreadyExists] if name is taken, or the new [RoleEntity].
     */
    suspend fun insertRole(
        name: String,
        description: String? = null
    ): Either<RoleError.RoleNameAlreadyExists, RoleEntity>

    /**
     * Updates an existing role's information.
     *
     * @param role The [RoleEntity] with updated information.
     * @return Either [RoleError] if update fails, or Unit on success.
     */
    suspend fun updateRole(role: RoleEntity): Either<RoleError, Unit>

    /**
     * Deletes a role by ID.
     *
     * @param id The unique identifier of the role to delete.
     * @return Either [RoleError.RoleNotFound] if not found, or Unit on success.
     */
    suspend fun deleteRole(id: Long): Either<RoleError.RoleNotFound, Unit>
}

