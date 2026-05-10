package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.auth.UserSecurityAlert
import eu.torvian.chatbot.common.models.api.auth.UserSessionInfo
import eu.torvian.chatbot.common.models.api.auth.UserTrustedDeviceInfo
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.api.auth.LoginResponse
import eu.torvian.chatbot.common.security.AccountValidationPolicy
import eu.torvian.chatbot.common.security.SecurityAuditStatus

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
     * @param deviceId Client-side UUID that persists across logins for device-based trust
     * @return Either an [ApiResourceError] on failure or [LoginResponse] with tokens on success
     */
    suspend fun login(username: String, password: String, deviceId: String): Either<ApiResourceError, LoginResponse>

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
     * Lists the authenticated user's active sessions.
     *
     * @return Either an [ApiResourceError] on failure or the ordered list of sessions on success
     */
    suspend fun getActiveSessions(): Either<ApiResourceError, List<UserSessionInfo>>

    /**
     * Logs out the current user or revokes a specific session on the server.
     *
     * When [sessionId] is `null`, the current bearer-backed session is invalidated and the
     * client can safely clear its cached token. When a value is provided, only that server-side
     * session is revoked.
     *
     * @param sessionId Optional session identifier to revoke instead of the current session
     * @return Either an [ApiResourceError] on failure or Unit on success
     */
    suspend fun logout(sessionId: Long? = null): Either<ApiResourceError, Unit>

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

    /**
     * Retrieves unacknowledged security alerts for the current user.
     * These alerts represent login attempts from unrecognized IP addresses that require user acknowledgment.
     *
     * @return Either an [ApiResourceError] on failure or the list of security alerts on success
     */
    suspend fun getSecurityAlerts(): Either<ApiResourceError, List<UserSecurityAlert>>

    /**
     * Retrieves the list of trusted devices for the current user.
     * These are devices that have been trusted through first use or security alert acknowledgement.
     *
     * Note: This operation is not available for restricted sessions.
     * @return Either an [ApiResourceError] on failure or the list of trusted devices on success
     */
    suspend fun getTrustedDevices(): Either<ApiResourceError, List<UserTrustedDeviceInfo>>

    /**
     * Revokes (deletes) a specific trusted device for the current user.
     * This removes the device from the trusted devices list.
     *
     * Note: This operation is not available for restricted sessions.
     * @param deviceId The device identifier to revoke
     * @return Either an [ApiResourceError] on failure or Unit on success
     */
    suspend fun revokeTrustedDevice(deviceId: String): Either<ApiResourceError, Unit>

    /**
     * Changes the password for the authenticated user.
     *
     * Requires the current password for verification. This operation is not available for restricted sessions.
     *
     * @param currentPassword The user's current password for verification
     * @param newPassword The new password to set
     * @return Either an [ApiResourceError] on failure or Unit on success
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): Either<ApiResourceError, Unit>

    /**
     * Changes the email address for the authenticated user.
     *
     * Requires the current password for verification. This operation is not available for restricted sessions.
     *
     * @param currentPassword The user's current password for verification
     * @param newEmail The new email address to set
     * @return Either an [ApiResourceError] on failure or [User] with updated user details on success
     */
    suspend fun changeEmail(currentPassword: String, newEmail: String): Either<ApiResourceError, User>

    /**
     * Completes a server-required password change for the authenticated user.
     *
     * This endpoint is used when the user is forced to change their password
     * (requiresPasswordChange = true). Unlike normal password change, it does not
     * require the current password.
     *
     * This operation is not available for restricted sessions.
     *
     * @param newPassword The new password to set
     * @return Either an [ApiResourceError] on failure or Unit on success
     */
    suspend fun completeRequiredPasswordChange(newPassword: String): Either<ApiResourceError, Unit>

    /**
     * Requests a device verification email for the current restricted session.
     *
     * This endpoint allows users on restricted sessions to request a verification email
     * that will allow them to promote their device to "Trusted" via an email link.
     *
     * @param deviceId The client-side UUID of the device to verify
     * @return Either an [ApiResourceError] on failure or Unit on success
     */
    suspend fun requestDeviceVerification(deviceId: String): Either<ApiResourceError, Unit>

    /**
     * Fetches the server's account validation policy.
     *
     * This endpoint is publicly accessible and returns the configured password
     * and username validation rules. The client uses this to dynamically adjust
     * its validation behavior and UI hints.
     *
     * @return Either an [ApiResourceError] on failure or [AccountValidationPolicy] on success
     */
    suspend fun getAuthPolicy(): Either<ApiResourceError, AccountValidationPolicy>

    /**
     * Resolves a single security alert with the specified outcome.
     *
     * This method allows the user to either trust or dismiss a specific security alert.
     * - TRUSTED: The device is added to the trusted devices list and the alert is marked as trusted.
     * - DISMISSED: The alert is marked as dismissed without adding the device to trusted devices.
     *
     * Note: This operation is not available for restricted sessions.
     *
     * @param alertId The unique identifier of the security alert to resolve.
     * @param outcome The outcome to apply (TRUSTED or DISMISSED).
     * @return Either an [ApiResourceError] on failure or Unit on success
     */
    suspend fun resolveSecurityAlert(alertId: Long, outcome: SecurityAuditStatus): Either<ApiResourceError, Unit>
}
