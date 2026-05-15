package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.server.domain.security.LoginResult
import eu.torvian.chatbot.server.domain.security.UserContext
import eu.torvian.chatbot.server.domain.security.WorkerContext
import eu.torvian.chatbot.server.service.security.error.RefreshTokenError
import io.ktor.server.auth.jwt.*

/**
 * Service for JWT token operations including refresh and validation.
 *
 * Handles token lifecycle management, including token refresh operations
 * and validation of JWT credentials from Ktor's auth pipeline.
 */
interface TokenService {
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
    suspend fun refreshToken(
        refreshToken: String,
        ipAddress: String?
    ): Either<RefreshTokenError, LoginResult>

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
}

