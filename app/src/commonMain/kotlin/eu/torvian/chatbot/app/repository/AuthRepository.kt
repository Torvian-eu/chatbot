package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.common.models.auth.LoginRequest
import eu.torvian.chatbot.common.models.auth.RegisterRequest
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
     * Authenticates a user with the provided credentials.
     * Updates the auth state and stores tokens on successful login.
     *
     * @param request The login credentials
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun login(request: LoginRequest): Either<RepositoryError, Unit>

    /**
     * Registers a new user account.
     * Note: This does NOT automatically log the user in after registration.
     *
     * @param request The registration details
     * @return Either a [RepositoryError] on failure or [User] with user details on success
     */
    suspend fun register(request: RegisterRequest): Either<RepositoryError, User>

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
}
