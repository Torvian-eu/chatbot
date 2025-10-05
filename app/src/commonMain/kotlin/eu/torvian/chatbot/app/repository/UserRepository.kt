package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.user.Role
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.models.user.UserWithDetails
import eu.torvian.chatbot.common.models.api.admin.ChangePasswordRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateUserRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing user accounts and roles (admin operations).
 *
 * This repository serves as the single source of truth for user data in the application,
 * providing reactive data streams through StateFlow and handling all user management operations.
 * It abstracts the underlying API layer and provides comprehensive error handling through
 * the RepositoryError hierarchy.
 *
 * All operations in this repository require admin privileges on the backend.
 */
interface UserRepository {

    /**
     * Reactive stream of all users in the system.
     *
     * This StateFlow provides real-time updates whenever user data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of all users wrapped in DataState
     */
    val users: StateFlow<DataState<RepositoryError, List<UserWithDetails>>>

    /**
     * Loads all users from the backend.
     *
     * This operation fetches the latest user data and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadUsers(): Either<RepositoryError, Unit>

    /**
     * Loads details for a specific user from the backend.
     *
     * This operation fetches the latest user data and updates the user in the internal list.
     *
     * @param userId The unique identifier of the user to load
     * @return Either.Right with the loaded UserWithDetails on success, or Either.Left with RepositoryError on failure
     */
    suspend fun loadUserDetails(userId: Long): Either<RepositoryError, UserWithDetails>

    /**
     * Updates a user's profile information (username, email).
     * Does NOT update password (use [changeUserPassword] for that).
     *
     * Upon successful update, the modified user replaces the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param userId The ID of the user to update
     * @param request The update request containing new username and email
     * @return Either.Right with the updated public User on success, or Either.Left with RepositoryError on failure
     */
    suspend fun updateUser(userId: Long, request: UpdateUserRequest): Either<RepositoryError, User>

    /**
     * Updates a user's status and returns the updated public User.
     */
    suspend fun updateUserStatus(userId: Long, status: UserStatus): Either<RepositoryError, User>

    /**
     * Updates a user's password change required flag and returns the updated public User.
     *
     * @param userId The ID of the user
     * @param requiresPasswordChange Whether the user must change their password on next login
     * @return Either.Right with the updated public User on success, or Either.Left with RepositoryError on failure
     */
    suspend fun updatePasswordChangeRequired(
        userId: Long,
        requiresPasswordChange: Boolean
    ): Either<RepositoryError, User>

    /**
     * Deletes a user account.
     * Cannot delete the last admin user in the system.
     *
     * Upon successful deletion, the user is automatically removed from the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param userId The unique identifier of the user to delete
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteUser(userId: Long): Either<RepositoryError, Unit>

    /**
     * Retrieves all roles assigned to a specific user.
     *
     * This method provides direct access to a user's roles without affecting
     * the main users StateFlow.
     *
     * @param userId The unique identifier of the user
     * @return Either.Right with a list of Role on success, or Either.Left with RepositoryError on failure
     */
    suspend fun getUserRoles(userId: Long): Either<RepositoryError, List<Role>>

    /**
     * Assigns a role to a user.
     *
     * This operation does not automatically update the users StateFlow.
     * Consumers should call [loadUserDetails] or [loadUsers] after this operation
     * if they need updated user data.
     *
     * @param userId The ID of the user
     * @param role The role to assign
     * @return Either.Right with Unit on successful assignment, or Either.Left with RepositoryError on failure
     */
    suspend fun assignRoleToUser(userId: Long, role: Role): Either<RepositoryError, Unit>

    /**
     * Revokes a role from a user.
     *
     * This operation does not automatically update the users StateFlow.
     * Consumers should call [loadUserDetails] or [loadUsers] after this operation
     * if they need updated user data.
     *
     * @param userId The ID of the user
     * @param role The role to revoke
     * @return Either.Right with Unit on successful revocation, or Either.Left with RepositoryError on failure
     */
    suspend fun revokeRoleFromUser(userId: Long, role: Role): Either<RepositoryError, Unit>

    /**
     * Changes a user's password (admin operation).
     *
     * @param userId The ID of the user
     * @param request The password change request containing the new password
     * @return Either.Right with Unit on successful password change, or Either.Left with RepositoryError on failure
     */
    suspend fun changeUserPassword(userId: Long, request: ChangePasswordRequest): Either<RepositoryError, Unit>
}
