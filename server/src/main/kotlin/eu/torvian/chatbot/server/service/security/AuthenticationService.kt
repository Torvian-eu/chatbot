package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.server.domain.security.LoginResult
import eu.torvian.chatbot.server.domain.security.UserContext
import eu.torvian.chatbot.server.domain.security.WorkerContext
import eu.torvian.chatbot.server.service.security.error.LoginError
import eu.torvian.chatbot.server.service.security.error.LogoutError
import eu.torvian.chatbot.server.service.security.error.LogoutAllError
import eu.torvian.chatbot.server.service.security.error.RefreshTokenError
import io.ktor.server.auth.jwt.*

/**
 * Service interface for user authentication and session management.
 *
 * This service handles user login/logout operations, JWT token generation and validation,
 * and session lifecycle management. It provides the core authentication functionality
 * for the multi-user system.
 */
interface AuthenticationService {
    /**
     * Authenticates a user with username and password.
     *
     * This method:
     * 1. Validates the provided credentials
     * 2. Creates a new user session
     * 3. Generates JWT access and refresh tokens
     * 4. Updates the user's last login timestamp
     *
     * @param username The username to authenticate
     * @param password The plaintext password to verify
     * @param ipAddress Optional IP address of the client for session tracking
     * @return Either [LoginError] if authentication fails, or [LoginResult] on success
     */
    suspend fun login(username: String, password: String, ipAddress: String?): Either<LoginError, LoginResult>

    /**
     * Logs out a user from their current session.
     *
     * This method invalidates only the user's current session (from which the request originates),
     * allowing other sessions on different devices to remain active.
     *
     * @param sessionId The unique identifier of the session to log out
     * @return Either [LogoutError] if logout fails, or Unit on success
     */
    suspend fun logout(sessionId: Long): Either<LogoutError, Unit>

    /**
     * Logs out a user from all their active sessions.
     *
     * This method invalidates all sessions for the user, effectively logging them out
     * from all devices and applications.
     *
     * @param userId The unique identifier of the user to log out from all sessions
     * @return Either [LogoutAllError] if logout fails, or Unit on success
     */
    suspend fun logoutAll(userId: Long): Either<LogoutAllError, Unit>

    /**
     * Validates JWT credentials from Ktor's auth pipeline.
     *
     * This method is called after Ktor has verified the token's signature and basic claims.
     * It performs additional business logic validation, such as:
     * 1. Checking if the associated database session is still valid.
     * 2. Fetching the full user details.
     * 3. Creating a UserContext for the request context.
     *
     * @param credential The validated JWT credential from Ktor.
     * @return A [UserContext] on success, or null if validation fails.
     */
    suspend fun validateCredential(credential: JWTCredential): UserContext?

    /**
     * Validates JWT credentials for worker/service principals.
     *
     * @param credential The validated JWT credential from Ktor for a worker/service token.
     * @return A [WorkerContext] on success, or null if validation fails.
     */
    suspend fun validateWorkerCredential(credential: JWTCredential): WorkerContext?

    /**
     * Refreshes an access token using a refresh token.
     *
     * This method:
     * 1. Validates the refresh token
     * 2. Checks if the associated session is still valid
     * 3. Generates new access and refresh tokens
     * 4. Deletes the old session and creates a new one
     *
     * @param refreshToken The JWT refresh token
     * @param ipAddress Optional IP address of the client for session tracking
     * @return Either [RefreshTokenError] if refresh fails, or [LoginResult] with new tokens
     */
    suspend fun refreshToken(refreshToken: String, ipAddress: String?): Either<RefreshTokenError, LoginResult>
}
