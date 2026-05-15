package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.domain.security.LoginResult
import eu.torvian.chatbot.server.service.security.error.LoginError
import eu.torvian.chatbot.server.service.security.error.LogoutError
import eu.torvian.chatbot.server.service.security.error.LogoutAllError

/**
 * Service interface for core user authentication and session management.
 *
 * This service handles user login/logout operations and session lifecycle management.
 * It provides the fundamental authentication functionality for the multi-user system.
 *
 * For JWT token operations, see [TokenService].
 * For device trust management, see [DeviceTrustService].
 * For security alerts, see [SecurityAuditService].
 * For account management, see [AccountManagementService].
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
     * @param deviceId Client-side UUID that persists across logins for device-based trust
     * @return Either [LoginError] if authentication fails, or [LoginResult] on success
     */
    suspend fun login(
        username: String,
        password: String,
        ipAddress: String?,
        deviceId: String
    ): Either<LoginError, LoginResult>

    /**
     * Logs out a user from their current session.
     *
     * This method invalidates only the specified session, allowing other sessions on different
     * devices to remain active. The method performs ownership validation to ensure the
     * requester can only log out sessions they own.
     *
     * @param userId The unique identifier of the authenticated user making the request
     * @param targetSessionId The unique identifier of the session to log out
     * @param requesterSessionId The session ID of the user making the request (for authorization)
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either [LogoutError] if logout fails, or Unit on success
     */
    suspend fun logout(
        userId: Long,
        targetSessionId: Long,
        requesterSessionId: Long,
        requesterIsRestricted: Boolean
    ): Either<LogoutError, Unit>

    /**
     * Logs out a user from all their active sessions.
     *
     * This method invalidates all sessions for the user, effectively logging them out
     * from all devices and applications.
     *
     * @param userId The unique identifier of the user to log out from all sessions
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either [LogoutAllError] if logout fails, or Unit on success
     */
    suspend fun logoutAll(userId: Long, requesterIsRestricted: Boolean): Either<LogoutAllError, Unit>

    /**
     * Retrieves the stored sessions for a user so they can inspect active logins.
     *
     * The returned list is owned exclusively by the requested user and is intended for
     * presentation in account-security views, not for cross-user administration.
     *
     * @param userId The unique identifier of the authenticated user.
     * @return A right-biased [Either] containing the user's session rows.
     */
    suspend fun getUserSessions(userId: Long): Either<Nothing, List<UserSessionEntity>>
}
