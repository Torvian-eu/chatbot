package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.UserRoleAssignmentError
import eu.torvian.chatbot.server.data.entities.RoleEntity

/**
 * Data Access Object for managing user-role assignments.
 *
 * This DAO provides operations for assigning and revoking roles to/from users,
 * as well as querying role assignments.
 */
interface UserRoleAssignmentDao {
    /**
     * Retrieves all roles assigned to a specific user.
     *
     * @param userId The ID of the user.
     * @return List of [RoleEntity] objects assigned to the user; empty list if none.
     */
    suspend fun getRolesByUserId(userId: Long): List<RoleEntity>

    /**
     * Retrieves all user IDs assigned to a specific role.
     *
     * @param roleId The ID of the role.
     * @return List of user IDs assigned to the role; empty list if none.
     */
    suspend fun getUserIdsByRoleId(roleId: Long): List<Long>

    /**
     * Assigns a role to a user.
     *
     * @param userId The ID of the user.
     * @param roleId The ID of the role to assign.
     * @return Either [UserRoleAssignmentError] if assignment fails, or Unit on success.
     */
    suspend fun assignRoleToUser(
        userId: Long,
        roleId: Long
    ): Either<UserRoleAssignmentError, Unit>

    /**
     * Revokes a role from a user.
     *
     * @param userId The ID of the user.
     * @param roleId The ID of the role to revoke.
     * @return Either [UserRoleAssignmentError.AssignmentNotFound] if not assigned, or Unit on success.
     */
    suspend fun revokeRoleFromUser(
        userId: Long,
        roleId: Long
    ): Either<UserRoleAssignmentError.AssignmentNotFound, Unit>

    /**
     * Checks if a user has a specific role.
     *
     * @param userId The ID of the user.
     * @param roleId The ID of the role.
     * @return true if the user has the role, false otherwise.
     */
    suspend fun hasRole(userId: Long, roleId: Long): Boolean
}

