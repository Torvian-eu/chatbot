package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.Role
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.common.models.UserStatus
import eu.torvian.chatbot.common.models.UserWithDetails
import eu.torvian.chatbot.server.service.core.error.auth.AssignRoleError
import eu.torvian.chatbot.server.service.core.error.auth.ChangePasswordError
import eu.torvian.chatbot.server.service.core.error.auth.DeleteUserError
import eu.torvian.chatbot.server.service.core.error.auth.RegisterUserError
import eu.torvian.chatbot.server.service.core.error.auth.RevokeRoleError
import eu.torvian.chatbot.server.service.core.error.auth.UserNotFoundError
import eu.torvian.chatbot.server.service.core.error.auth.UpdateUserError

/**
 * Service interface for user management operations.
 *
 * This service provides high-level operations for user account management including
 * registration, lookup, and profile updates. It handles business logic such as
 * password validation, automatic group assignment, and user lifecycle management.
 */
interface UserService {
    /**
     * Registers a new user account with automatic group assignment.
     *
     * This method:
     * 1. Validates password strength
     * 2. Hashes the password securely
     * 3. Creates the user account (disabled by default)
     * 4. Automatically adds the user to the "All Users" group
     *
     * @param username Unique username for the new user
     * @param password Plaintext password (will be hashed)
     * @param email Optional email address (must be unique if provided)
     * @return Either [RegisterUserError] if registration fails, or the newly created [User]
     */
    suspend fun registerUser(
        username: String,
        password: String,
        email: String? = null
    ): Either<RegisterUserError, User>

    /**
     * Retrieves a user by their username.
     *
     * @param username The username to search for
     * @return Either [UserNotFoundError.ByUsername] if not found, or the [User]
     */
    suspend fun getUserByUsername(username: String): Either<UserNotFoundError.ByUsername, User>

    /**
     * Retrieves a user by their unique ID.
     *
     * @param id The user ID to search for
     * @return Either [UserNotFoundError.ById] if not found, or the [User]
     */
    suspend fun getUserById(id: Long): Either<UserNotFoundError.ById, User>

    /**
     * Updates a user's last login timestamp.
     *
     * @param userId The unique identifier of the user
     * @return Either [UserNotFoundError.ById] if user not found, or Unit on success
     */
    suspend fun updateLastLogin(userId: Long): Either<UserNotFoundError.ById, Unit>

    /**
     * Retrieves all users in the system.
     *
     * Note: This method is typically restricted to administrators.
     *
     * @return List of all [User] objects; empty list if no users exist
     */
    suspend fun getAllUsers(): List<User>

    // --- Admin Operations ---

    /**
     * Returns all users with their roles and group memberships for admin UI.
     */
    suspend fun getAllUsersWithDetails(): List<UserWithDetails>

    /**
     * Returns a specific user with roles and groups for admin UI.
     */
    suspend fun getUserWithDetails(userId: Long): Either<UserNotFoundError.ById, UserWithDetails>

    /**
     * Updates a user's status (ACTIVE, DISABLED, LOCKED) and returns updated public [User].
     */
    suspend fun updateUserStatus(userId: Long, status: UserStatus): Either<UpdateUserError, User>

    /**
     * Updates a user's profile information (admin only). Does NOT update password (use changePassword for that).
     *
     * @param userId The ID of the user to update
     * @param username New username (must be unique if changed)
     * @param email New email (must be unique if provided)
     * @return Either [UpdateUserError] if update fails, or the updated [User]
     */
    suspend fun updateUser(
        userId: Long,
        username: String,
        email: String?
    ): Either<UpdateUserError, User>

    /**
     * Deletes a user account (admin only). Cannot delete the last administrator in the system.
     *
     * @param userId The ID of the user to delete
     * @return Either [DeleteUserError] if deletion fails, or Unit on success
     */
    suspend fun deleteUser(userId: Long): Either<DeleteUserError, Unit>

    /**
     * Assigns a role to a user (admin only).
     *
     * @param userId The ID of the user
     * @param roleId The ID of the role to assign
     * @return Either [AssignRoleError] if assignment fails, or Unit on success
     */
    suspend fun assignRoleToUser(
        userId: Long,
        roleId: Long
    ): Either<AssignRoleError, Unit>

    /**
     * Revokes a role from a user (admin only). Cannot revoke admin role from the last administrator.
     *
     * @param userId The ID of the user
     * @param roleId The ID of the role to revoke
     * @return Either [RevokeRoleError] if revocation fails, or Unit on success
     */
    suspend fun revokeRoleFromUser(
        userId: Long,
        roleId: Long
    ): Either<RevokeRoleError, Unit>

    /**
     * Retrieves all roles assigned to a user.
     *
     * @param userId The ID of the user
     * @return List of [Role] objects assigned to the user; empty list if none
     */
    suspend fun getUserRoles(userId: Long): List<Role>

    /**
     * Changes a user's password (can be used by admin or user themselves).
     *
     * @param userId The ID of the user
     * @param newPassword The new plaintext password (will be hashed)
     * @return Either [ChangePasswordError] if change fails, or Unit on success
     */
    suspend fun changePassword(
        userId: Long,
        newPassword: String
    ): Either<ChangePasswordError, Unit>
}
