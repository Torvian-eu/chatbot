package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.server.domain.security.LoginResult
import eu.torvian.chatbot.server.domain.security.UserContext
import eu.torvian.chatbot.server.service.security.error.LoginError
import eu.torvian.chatbot.server.service.security.error.LogoutError
import eu.torvian.chatbot.server.service.security.error.RefreshTokenError
import eu.torvian.chatbot.server.service.security.error.TokenValidationError

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
     * @return Either [LoginError] if authentication fails, or [LoginResult] on success
     */
    suspend fun login(username: String, password: String): Either<LoginError, LoginResult>
    
    /**
     * Logs out a user by invalidating their session.
     * 
     * This method invalidates the user's current session, making all tokens
     * associated with that session invalid.
     * 
     * @param userId The unique identifier of the user to log out
     * @return Either [LogoutError] if logout fails, or Unit on success
     */
    suspend fun logout(userId: Long): Either<LogoutError, Unit>
    
    /**
     * Validates a JWT token and returns the user context.
     * 
     * This method:
     * 1. Verifies the JWT token signature and claims
     * 2. Checks if the associated session is still valid
     * 3. Returns the user context for authorization
     * 
     * @param token The JWT token to validate
     * @return Either [TokenValidationError] if validation fails, or [UserContext] on success
     */
    suspend fun validateToken(token: String): Either<TokenValidationError, UserContext>
    
    /**
     * Refreshes an access token using a refresh token.
     * 
     * This method:
     * 1. Validates the refresh token
     * 2. Checks if the associated session is still valid
     * 3. Generates new access and refresh tokens
     * 
     * @param refreshToken The JWT refresh token
     * @return Either [RefreshTokenError] if refresh fails, or [LoginResult] with new tokens
     */
    suspend fun refreshToken(refreshToken: String): Either<RefreshTokenError, LoginResult>
}
