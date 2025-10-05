package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.common.models.api.auth.LoginRequest
import eu.torvian.chatbot.common.models.api.auth.LoginResponse
import eu.torvian.chatbot.common.models.api.auth.RefreshTokenRequest
import eu.torvian.chatbot.common.models.api.auth.RegisterRequest

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
     * @param request The refresh token request containing the refresh token
     * @return Either an [ApiResourceError] on failure or [LoginResponse] with new tokens on success
     */
    suspend fun refreshToken(request: RefreshTokenRequest): Either<ApiResourceError, LoginResponse>

    /**
     * Authenticates a user with username/email and password.
     *
     * @param request The login request containing credentials
     * @return Either an [ApiResourceError] on failure or [LoginResponse] with tokens on success
     */
    suspend fun login(request: LoginRequest): Either<ApiResourceError, LoginResponse>

    /**
     * Registers a new user account.
     * Note: This does NOT automatically log the user in - it only creates the account.
     *
     * @param request The registration request containing user details
     * @return Either an [ApiResourceError] on failure or [User] with user details on success
     */
    suspend fun register(request: RegisterRequest): Either<ApiResourceError, User>

    /**
     * Logs out the current user by invalidating tokens on the server.
     *
     * @return Either an [ApiResourceError] on failure or Unit on success
     */
    suspend fun logout(): Either<ApiResourceError, Unit>

    /**
     * Gets the current authenticated user details.
     * Used to validate tokens and restore authentication state.
     *
     * @return Either an [ApiResourceError] on failure or [User] with user details on success
     */
    suspend fun getCurrentUser(): Either<ApiResourceError, User>
}
