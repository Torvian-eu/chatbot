package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.auth.UserTrustedDeviceInfo
import eu.torvian.chatbot.server.data.entities.SecurityAuditEntity
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.domain.security.LoginResult
import eu.torvian.chatbot.server.domain.security.UserContext
import eu.torvian.chatbot.server.domain.security.WorkerContext
import eu.torvian.chatbot.server.service.security.error.*
import eu.torvian.chatbot.server.service.security.error.RevokeTrustedDeviceError.DeviceNotFound
import eu.torvian.chatbot.server.service.security.error.RevokeTrustedDeviceError.InsufficientPermissions
import eu.torvian.chatbot.server.service.core.error.auth.ChangePasswordError
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

    /**
     * Retrieves unacknowledged security alerts for a user.
     *
     * Returns detailed information about unrecognized device logins that have not been
     * acknowledged by the user yet. These are sourced from the SecurityAuditDao.
     *
     * @param userId The unique identifier of the authenticated user.
     * @return A right-biased [Either] containing the list of unacknowledged security alerts.
     */
    suspend fun getSecurityAlerts(userId: Long): Either<Nothing, List<SecurityAuditEntity>>

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

    /**
     * Marks every unacknowledged security alert for a user as acknowledged.
     *
     * This is the server-side confirmation step used after the UI shows a security warning.
     * It also promotes the devices from the acknowledged alerts to the trusted devices table,
     * allowing future logins from those devices to be unrestricted.
     *
     * Restricted sessions cannot acknowledge alerts - this prevents self-acknowledgement of
     * untrusted devices. The caller must verify the session is not restricted before calling.
     *
     * @param userId The unique identifier of the authenticated user.
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either [AcknowledgeAlertsError] if acknowledgement fails, or Unit on success
     */
    suspend fun acknowledgeSecurityAlerts(
        userId: Long,
        requesterIsRestricted: Boolean
    ): Either<AcknowledgeAlertsError, Unit>

    /**
     * Retrieves the list of trusted devices for a user.
     *
     * Returns all devices that have been trusted for the user, either through
     * Trust on First Use (first device) or by acknowledging security alerts.
     *
     * Restricted sessions cannot list trusted devices - this prevents enumeration
     * attacks on unverified devices.
     *
     * @param userId The unique identifier of the authenticated user.
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either [InsufficientPermissions] if restricted, or the list of trusted devices on success
     */
    suspend fun getTrustedDevices(
        userId: Long,
        requesterIsRestricted: Boolean
    ): Either<RevokeTrustedDeviceError, List<UserTrustedDeviceInfo>>

    /**
     * Revokes (deletes) a specific trusted device for a user.
     *
     * This removes the device from the trusted devices list, causing future logins
     * from that device to require verification (if security mode is WARNING or STRICT).
     *
     * Restricted sessions cannot revoke devices - this prevents malicious actors on
     * unverified devices from removing trust from other devices.
     *
     * @param userId The unique identifier of the authenticated user.
     * @param deviceId The device identifier to revoke.
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either an error ([InsufficientPermissions] or [DeviceNotFound]) or Unit on success
     */
    suspend fun revokeTrustedDevice(
        userId: Long,
        deviceId: String,
        requesterIsRestricted: Boolean
    ): Either<RevokeTrustedDeviceError, Unit>

    /**
     * Changes the password for an authenticated user.
     *
     * This method:
     * 1. Checks if the requester is restricted (untrusted session) - blocked if so
     * 2. Verifies the current password matches the stored hash
     * 3. Validates the new password meets strength requirements
     * 4. Updates the password and clears the requiresPasswordChange flag
     *
     * @param userId The unique identifier of the user changing their password
     * @param currentPassword The user's current password for verification
     * @param newPassword The new password to set
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either [ChangePasswordError] if the operation fails, or Unit on success
     */
    suspend fun changePassword(
        userId: Long,
        currentPassword: String,
        newPassword: String,
        requesterIsRestricted: Boolean
    ): Either<ChangePasswordError, Unit>

    /**
     * Completes a server-required password change for an authenticated user.
     *
     * This method is used when a user is forced to change their password
     * (requiresPasswordChange = true). Unlike normal password change, it does not
     * require the current password, but:
     * 1. Checks if the requester is restricted (untrusted session) - blocked if so
     * 2. Verifies the user's requiresPasswordChange flag is true
     * 3. Validates the new password meets strength requirements
     * 4. Updates the password and clears the requiresPasswordChange flag
     *
     * @param userId The unique identifier of the user changing their password
     * @param newPassword The new password to set
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either [CompleteRequiredPasswordChangeError] if the operation fails, or Unit on success
     */
    suspend fun completeRequiredPasswordChange(
        userId: Long,
        newPassword: String,
        requesterIsRestricted: Boolean
    ): Either<CompleteRequiredPasswordChangeError, Unit>
}
