package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.Role
import eu.torvian.chatbot.server.service.core.error.auth.CreateRoleError
import eu.torvian.chatbot.server.service.core.error.auth.DeleteRoleError
import eu.torvian.chatbot.server.service.core.error.auth.RoleNotFoundError
import eu.torvian.chatbot.server.service.core.error.auth.UpdateRoleError

/**
 * Service interface for role management operations.
 *
 * This service provides high-level operations for role CRUD operations including
 * creation, retrieval, updates, and deletion. It handles business logic such as
 * protecting system roles from modification and ensuring role names are unique.
 */
interface RoleService {
    /**
     * Retrieves all roles in the system.
     *
     * This method returns all available roles, including system roles (Admin, StandardUser)
     * and custom roles created by administrators.
     *
     * @return List of all [Role] objects; empty list if no roles exist
     */
    suspend fun getAllRoles(): List<Role>

    /**
     * Retrieves a role by its unique ID.
     *
     * @param id The unique identifier of the role
     * @return Either [RoleNotFoundError.ById] if not found, or the [Role]
     */
    suspend fun getRoleById(id: Long): Either<RoleNotFoundError.ById, Role>

    /**
     * Creates a new role with the specified name and description.
     *
     * This method validates the role name and ensures it doesn't conflict with
     * existing roles. Only administrators with the MANAGE_ROLES permission
     * can create new roles.
     *
     * @param name Unique name for the role (case-sensitive)
     * @param description Optional description explaining the role's purpose
     * @return Either [CreateRoleError] if creation fails, or the newly created [Role]
     */
    suspend fun createRole(
        name: String,
        description: String?
    ): Either<CreateRoleError, Role>

    /**
     * Updates an existing role's name and/or description.
     *
     * System roles (Admin, StandardUser) are protected from name changes but
     * their descriptions can be updated. Only administrators with the MANAGE_ROLES
     * permission can update roles.
     *
     * @param id The unique identifier of the role to update
     * @param name The new name for the role (must be unique)
     * @param description The new description for the role
     * @return Either [UpdateRoleError] if update fails, or the updated [Role]
     */
    suspend fun updateRole(
        id: Long,
        name: String,
        description: String?
    ): Either<UpdateRoleError, Role>

    /**
     * Deletes a role by its unique ID.
     *
     * System roles (Admin, StandardUser) cannot be deleted. Additionally,
     * roles that are currently assigned to users cannot be deleted to maintain
     * data integrity. Only administrators with the MANAGE_ROLES permission
     * can delete roles.
     *
     * @param id The unique identifier of the role to delete
     * @return Either [DeleteRoleError] if deletion fails, or Unit on success
     */
    suspend fun deleteRole(id: Long): Either<DeleteRoleError, Unit>
}
