package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.user.Role
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.models.user.UserWithDetails

/**
 * Frontend API interface for interacting with User Management endpoints (admin only).
 *
 * This interface defines the operations for managing user accounts, roles, and permissions.
 * All operations require admin privileges on the backend.
 * All methods are suspend functions and return [Either<ApiResourceError, T>].
 */
interface UserApi {
    /**
     * Retrieves a list of all users in the system.
     *
     * Corresponds to `GET /api/v1/users`.
     * Requires admin permission: MANAGE_USERS
     *
     * @return [Either.Right] containing a list of [User] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getAllUsers(): Either<ApiResourceError, List<User>>

    /**
     * Retrieves a specific user.
     *
     * Corresponds to `GET /api/v1/users/{userId}`.
     * Requires admin permission: MANAGE_USERS
     *
     * @param userId The ID of the user to retrieve.
     * @return [Either.Right] containing the requested [User] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun getUserById(userId: Long): Either<ApiResourceError, User>

    /**
     * Retrieves a list of all users in the system, including roles and groups.
     *
     * Corresponds to `GET /api/v1/users/detailed`.
     * Requires admin permission: MANAGE_USERS
     *
     * @return [Either.Right] containing a list of [UserWithDetails] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getAllUsersWithDetails(): Either<ApiResourceError, List<UserWithDetails>>

    /**
     * Retrieves details for a specific user, including roles and groups.
     *
     * Corresponds to `GET /api/v1/users/{userId}/detailed`.
     * Requires admin permission: MANAGE_USERS
     *
     * @param userId The ID of the user to retrieve.
     * @return [Either.Right] containing the requested [UserWithDetails] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun getUserWithDetails(userId: Long): Either<ApiResourceError, UserWithDetails>


    /**
     * Updates a user's profile information (username, email).
     * Does NOT update password (use [changeUserPassword] for that).
     *
     * Corresponds to `PUT /api/v1/users/{userId}`.
     * Requires admin permission: MANAGE_USERS
     *
     * @param userId The ID of the user to update.
     * @param username The new username for the user.
     * @param email The new email address for the user (optional).
     * @return [Either.Right] containing the updated public [User] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updateUser(userId: Long, username: String, email: String?): Either<ApiResourceError, User>

    /**
     * Updates a user's status (ACTIVE, DISABLED, LOCKED) and returns updated public [User].
     *
     * Corresponds to `PUT /api/v1/users/{userId}/status`.
     * Requires admin permission: MANAGE_USERS
     *
     * @param userId The ID of the user to update.
     * @param status The new status to apply to the user.
     * @return [Either.Right] containing the updated public [User] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updateUserStatus(userId: Long, status: UserStatus): Either<ApiResourceError, User>

    /**
     * Updates a user's password change required flag.
     *
     * Corresponds to `PUT /api/v1/users/{userId}/password-change-required`.
     * Requires admin permission: MANAGE_USERS
     *
     * @param userId The ID of the user
     * @param requiresPasswordChange Whether the user must change their password on next login
     * @return [Either.Right] containing the updated public [User] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updatePasswordChangeRequired(
        userId: Long,
        requiresPasswordChange: Boolean
    ): Either<ApiResourceError, User>

    /**
     * Deletes a user account.
     * Cannot delete the last admin user in the system.
     *
     * Corresponds to `DELETE /api/v1/users/{userId}`.
     * Requires admin permission: MANAGE_USERS
     *
     * @param userId The ID of the user to delete.
     * @return [Either.Right] with [Unit] on successful deletion,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun deleteUser(userId: Long): Either<ApiResourceError, Unit>

    /**
     * Retrieves all roles assigned to a specific user.
     *
     * Corresponds to `GET /api/v1/users/{userId}/roles`.
     * Requires admin permission: MANAGE_USERS
     *
     * @param userId The ID of the user.
     * @return [Either.Right] containing a list of [Role] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getUserRoles(userId: Long): Either<ApiResourceError, List<Role>>

    /**
     * Assigns a role to a user.
     *
     * Corresponds to `POST /api/v1/users/{userId}/roles`.
     * Requires admin permission: MANAGE_USERS
     *
     * @param userId The ID of the user.
     * @param roleId The ID of the role to assign.
     * @return [Either.Right] with [Unit] on successful assignment,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun assignRoleToUser(userId: Long, roleId: Long): Either<ApiResourceError, Unit>

    /**
     * Revokes a role from a user.
     *
     * Corresponds to `DELETE /api/v1/users/{userId}/roles/{roleId}`.
     * Requires admin permission: MANAGE_USERS
     *
     * @param userId The ID of the user.
     * @param roleId The ID of the role to revoke.
     * @return [Either.Right] with [Unit] on successful revocation,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun revokeRoleFromUser(userId: Long, roleId: Long): Either<ApiResourceError, Unit>

    /**
     * Changes a user's password (admin operation).
     *
     * Corresponds to `PUT /api/v1/users/{userId}/password`.
     * Requires admin permission: MANAGE_USERS OR changing own password
     *
     * @param userId The ID of the user.
     * @param newPassword The new password for the user.
     * @return [Either.Right] with [Unit] on successful password change,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun changeUserPassword(userId: Long, newPassword: String): Either<ApiResourceError, Unit>
}
