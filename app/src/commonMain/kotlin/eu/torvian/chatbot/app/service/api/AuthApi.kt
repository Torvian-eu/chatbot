package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.api.auth.LoginResponse

/**
 * Authentication API client interface for managing user authentication operations.
 *
 * This interface provides methods for login, registration, token refresh, and logout
 * operations with the backend authentication service.
 */
interface AuthApi {

    /**
     * Refreshes an expired access token using a valid refresh token.
     *
     * @param refreshToken The refresh token to use for obtaining a new access token
     * @return Either an [ApiResourceError] on failure or [LoginResponse] with new tokens on success
     */
    suspend fun refreshToken(refreshToken: String): Either<ApiResourceError, LoginResponse>

    /**
     * Authenticates a user with username/email and password.
     *
     * @param username The username or email for authentication
     * @param password The password for authentication
     * @return Either an [ApiResourceError] on failure or [LoginResponse] with tokens on success
     */
    suspend fun login(username: String, password: String): Either<ApiResourceError, LoginResponse>

    /**
     * Registers a new user account.
     * Note: This does NOT automatically log the user in - it only creates the account.
     *
     * @param username The username for the new account
     * @param password The password for the new account
     * @param email Optional email address for the new account
     * @return Either an [ApiResourceError] on failure or [User] with user details on success
     */
    suspend fun register(username: String, password: String, email: String? = null): Either<ApiResourceError, User>

    /**
     * Logs out the current user by invalidating tokens on the server.
     *
     * @return Either an [ApiResourceError] on failure or Unit on success
     */
    suspend fun logout(): Either<ApiResourceError, Unit>

    /**
     * Logs the current user out from every active session on the server.
     *
     * @return Either an [ApiResourceError] on failure or Unit on success
     */
    suspend fun logoutAll(): Either<ApiResourceError, Unit>

    /**
     * Gets the current authenticated user details.
     * Used to validate tokens and restore authentication state.
     *
     * @return Either an [ApiResourceError] on failure or [User] with user details on success
     */
    suspend fun getCurrentUser(): Either<ApiResourceError, User>

    /**
     * Clears the currently stored token from the in-memory cache.
     * This is used when the token must be reloaded from storage. For example, when switching accounts
     */
    suspend fun clearToken()
}
