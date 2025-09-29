package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.RolePermissionError

/**
 * Data Access Object for managing role-permission assignments.
 *
 * This DAO provides operations for assigning and revoking permissions to/from roles.
 */
interface RolePermissionDao {
    /**
     * Assigns a permission to a role.
     *
     * @param roleId The ID of the role.
     * @param permissionId The ID of the permission to assign.
     * @return Either [RolePermissionError] if assignment fails, or Unit on success.
     */
    suspend fun assignPermissionToRole(
        roleId: Long,
        permissionId: Long
    ): Either<RolePermissionError, Unit>

    /**
     * Revokes a permission from a role.
     *
     * @param roleId The ID of the role.
     * @param permissionId The ID of the permission to revoke.
     * @return Either [RolePermissionError.AssignmentNotFound] if not assigned, or Unit on success.
     */
    suspend fun revokePermissionFromRole(
        roleId: Long,
        permissionId: Long
    ): Either<RolePermissionError.AssignmentNotFound, Unit>
}

