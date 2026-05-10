package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.common.models.api.auth.UserSecurityAlert
import eu.torvian.chatbot.common.models.api.auth.UserSessionInfo
import eu.torvian.chatbot.common.models.api.auth.UserTrustedDeviceInfo
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.security.SecurityAuditStatus
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
     * Loads the authenticated user's active sessions from the server.
     *
     * @return Either a [RepositoryError] on failure or the current session list on success
     */
    suspend fun getActiveSessions(): Either<RepositoryError, List<UserSessionInfo>>

    /**
     * Revokes a specific session belonging to the authenticated user.
     *
     * This is intended for security management UI where the user can remove a session from
     * another device without logging out the current browser or app instance.
     *
     * @param sessionId The session identifier to revoke
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun revokeSession(sessionId: Long): Either<RepositoryError, Unit>

    /**
     * Logs out the current user by clearing tokens and updating auth state.
     * Only clears local tokens after successful server logout.
     *
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun logout(): Either<RepositoryError, Unit>

    /**
     * Logs the current user out from all server-side sessions and clears local auth data.
     *
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun logoutAll(): Either<RepositoryError, Unit>

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

    /**
     * Retrieves unacknowledged security alerts for the current user.
     * These alerts represent login attempts from untrusted or unrecognized devices.
     *
     * Note: This operation is not available for restricted sessions.
     *
     * @return Either a [RepositoryError] on failure or the list of security alerts on success
     */
    suspend fun getSecurityAlerts(): Either<RepositoryError, List<UserSecurityAlert>>

    /**
     * Retrieves the list of trusted devices for the current user.
     * These are devices that have been trusted through first use or security alert acknowledgement.
     *
     * Note: This operation is not available for restricted sessions.
     * @return Either a [RepositoryError] on failure or the list of trusted devices on success
     */
    suspend fun getTrustedDevices(): Either<RepositoryError, List<UserTrustedDeviceInfo>>

    /**
     * Revokes (deletes) a specific trusted device for the current user.
     * This removes the device from the trusted devices list.
     *
     * Note: This operation is not available for restricted sessions.
     * @param deviceId The device identifier to revoke
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun revokeTrustedDevice(deviceId: String): Either<RepositoryError, Unit>

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
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun resolveSecurityAlert(alertId: Long, outcome: SecurityAuditStatus): Either<RepositoryError, Unit>

    /**
     * Changes the password for the authenticated user.
     *
     * Requires the current password for verification. This operation is not available for restricted sessions.
     *
     * @param currentPassword The user's current password for verification
     * @param newPassword The new password to set
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): Either<RepositoryError, Unit>

    /**
     * Requests a device verification email for the current restricted session.
     *
     * This allows users on restricted sessions to request a verification email
     * that will allow them to promote their device to "Trusted" via an email link.
     *
     * @param deviceId The client-side UUID of the device to verify
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun requestDeviceVerification(deviceId: String): Either<RepositoryError, Unit>

    /**
     * Refreshes the current session by obtaining a new token using the stored refresh token.
     *
     * This is used to check if the user's session has been promoted from restricted to unrestricted
     * (e.g., after clicking a device verification email link).
     *
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun refreshSession(): Either<RepositoryError, Unit>

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
     * @return Either a [RepositoryError] on failure or Unit on success
     */
    suspend fun completeRequiredPasswordChange(newPassword: String): Either<RepositoryError, Unit>
}
