package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import eu.torvian.chatbot.common.models.user.Permission
import eu.torvian.chatbot.common.models.user.User
import kotlin.time.Instant

/**
 * Platform-agnostic interface for secure token storage operations.
 *
 * This interface provides a contract for storing and retrieving authentication tokens
 * and user data across different platforms (desktop, web). All operations return Arrow's Either
 * type to provide consistent error handling without exceptions.
 *
 * Implementations should:
 * - Encrypt tokens and user data before storage for security
 * - Handle platform-specific storage mechanisms (files, localStorage, etc.)
 * - Map all storage errors to appropriate [TokenStorageError] types
 * - Provide thread-safe operations where applicable
 * - Support multiple user accounts with account switching capabilities
 */
interface TokenStorage {

    /**
     * Saves authentication data (tokens, user info, and permissions) to secure storage.
     *
     * @param accessToken The JWT access token for API authentication
     * @param refreshToken The refresh token for obtaining new access tokens
     * @param expiresAt The expiration timestamp for the access token
     * @param user The authenticated user data for optimistic authentication
     * @param permissions The list of permissions granted to the user
     * @return Either a [TokenStorageError] on failure or Unit on success
     */
    suspend fun saveAuthData(
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
        user: User,
        permissions: List<Permission>
    ): Either<TokenStorageError, Unit>

    /**
     * Retrieves cached account data for optimistic authentication, for the currently active account.
     *
     * @return Either a [TokenStorageError] on failure or the account data on success
     */
    suspend fun getAccountData(): Either<TokenStorageError, AccountData>

    /**
     * Retrieves cached account data for optimistic authentication, for a specific user.
     *
     * @param userId The ID of the user account to retrieve
     * @return Either a [TokenStorageError] on failure or the account data on success
     */
    suspend fun getAccountData(userId: Long): Either<TokenStorageError, AccountData>

    /**
     * Clears all stored authentication data (tokens and user info) from storage, for the currently active account.
     * This operation should be performed during logout or when tokens are invalidated.
     *
     * @return Either a [TokenStorageError] on failure or Unit on success
     */
    suspend fun clearAuthData(): Either<TokenStorageError, Unit>

    /**
     * Retrieves the stored access token, for the currently active account.
     *
     * @return Either a [TokenStorageError] on failure or the access token on success
     */
    suspend fun getAccessToken(): Either<TokenStorageError, String>

    /**
     * Retrieves the stored refresh token, for the currently active account.
     *
     * @return Either a [TokenStorageError] on failure or the refresh token on success
     */
    suspend fun getRefreshToken(): Either<TokenStorageError, String>

    /**
     * Retrieves the expiration timestamp of the stored access token, for the currently active account.
     *
     * @return Either a [TokenStorageError] on failure or the expiry timestamp on success
     */
    suspend fun getExpiry(): Either<TokenStorageError, Instant>

    /**
     * Lists all stored accounts with their metadata.
     *
     * This method returns information about all accounts that have been stored,
     * ordered by most recently used first. It does not include sensitive authentication
     * data like tokens.
     *
     * @return Either a [TokenStorageError] on failure or list of stored accounts on success
     */
    suspend fun listStoredAccounts(): Either<TokenStorageError, List<AccountData>>

    /**
     * Switches the active account to the specified user.
     *
     * This changes which account's tokens are returned by other methods like
     * [getAccessToken], [getAccountData], etc. The operation will fail if the
     * specified account does not exist or if its data files are missing.
     *
     * @param userId The ID of the user account to switch to
     * @return Either a [TokenStorageError] on failure or Unit on success
     */
    suspend fun switchAccount(userId: Long): Either<TokenStorageError, Unit>

    /**
     * Gets the currently active account's user ID.
     *
     * Returns null if no account is currently active (e.g., after logout or
     * when all accounts have been removed).
     *
     * @return Either a [TokenStorageError] on failure or the active userId (null if none) on success
     */
    suspend fun getCurrentAccountId(): Either<TokenStorageError, Long?>

    /**
     * Removes a specific account from storage.
     *
     * This permanently deletes all data associated with the account, including
     * tokens and user information. If the removed account is currently active,
     * the activeUserId is set to null.
     *
     * @param userId The ID of the account to remove
     * @return Either a [TokenStorageError] on failure or Unit on success
     */
    suspend fun removeAccount(userId: Long): Either<TokenStorageError, Unit>

}
