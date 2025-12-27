package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.common.models.user.User
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing user authentication state and operations.
 *
 * This repository provides a reactive interface for authentication operations
 * and maintains the current authentication state of the application.
 */
interface AuthRepository {

    /**
     * The current authentication state as a reactive StateFlow.
     * This allows UI components to observe authentication changes.
     */
    val authState: StateFlow<AuthState>

    /**
     * The list of available user accounts as a reactive StateFlow.
     * This allows UI components to observe changes to the available accounts list.
     */
    val availableAccounts: StateFlow<List<AccountData>>

    /**
     * Authenticates a user with the provided credentials.
     * Updates the auth state and stores tokens on successful login.
     *
     * @param username The username to authenticate
     * @param password The plaintext password to verify
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun login(username: String, password: String): Either<RepositoryError, Unit>

    /**
     * Registers a new user account.
     * Note: This does NOT automatically log the user in after registration.
     *
     * @param username Unique username for the new user account
     * @param password Plaintext password (will be hashed server-side)
     * @param email Optional email address for the user (must be unique if provided)
     * @return Either a [RepositoryError] on failure or [User] with user details on success
     */
    suspend fun register(username: String, password: String, email: String? = null): Either<RepositoryError, User>

    /**
     * Changes the password for the currently authenticated user.
     * This is used when the user is forced to change their password on first login.
     *
     * @param userId The ID of the user changing their password
     * @param newPassword The new password to set
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun changePassword(userId: Long, newPassword: String): Either<RepositoryError, Unit>

    /**
     * Logs out the current user by clearing tokens and updating auth state.
     * Only clears local tokens after successful server logout.
     *
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun logout(): Either<RepositoryError, Unit>

    /**
     * Checks if the user is currently authenticated by checking the auth state.
     *
     * @return true if the user has valid authentication tokens, false otherwise
     */
    suspend fun isAuthenticated(): Boolean

    /**
     * Checks the initial authentication state on app startup.
     * Validates existing tokens with the server and updates the auth state accordingly.
     * Sets state to Loading initially, then either Authenticated or Unauthenticated based on token validation.
     */
    suspend fun checkInitialAuthState()

    /**
     * Switches the active account to the specified user.
     *
     * This updates the auth state and reloads user data without calling logout.
     * The account must exist in local storage and have valid stored credentials.
     * This operation does not make a network call - it switches to locally stored data.
     *
     * @param userId The ID of the user account to switch to
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun switchAccount(userId: Long): Either<RepositoryError, Unit>

    /**
     * Removes a specific account from local storage.
     *
     * This permanently deletes all locally stored data for the specified account,
     * including tokens and user information. If the removed account is currently active,
     * the auth state becomes Unauthenticated.
     *
     * @param userId The ID of the account to remove
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun removeAccount(userId: Long): Either<RepositoryError, Unit>
}
