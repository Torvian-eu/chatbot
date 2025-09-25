package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import kotlinx.datetime.Instant

/**
 * Platform-agnostic interface for secure token storage operations.
 *
 * This interface provides a contract for storing and retrieving authentication tokens
 * across different platforms (desktop, web). All operations return Arrow's Either
 * type to provide consistent error handling without exceptions.
 *
 * Implementations should:
 * - Encrypt tokens before storage for security
 * - Handle platform-specific storage mechanisms (files, localStorage, etc.)
 * - Map all storage errors to appropriate [TokenStorageError] types
 * - Provide thread-safe operations where applicable
 */
interface TokenStorage {

    /**
     * Saves authentication tokens to secure storage.
     *
     * @param accessToken The JWT access token for API authentication
     * @param refreshToken The refresh token for obtaining new access tokens
     * @param expiresAt The expiration timestamp for the access token
     * @return Either a [TokenStorageError] on failure or Unit on success
     */
    suspend fun saveTokens(
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant
    ): Either<TokenStorageError, Unit>

    /**
     * Retrieves the stored access token.
     *
     * @return Either a [TokenStorageError] on failure or the access token on success
     */
    suspend fun getAccessToken(): Either<TokenStorageError, String>

    /**
     * Retrieves the stored refresh token.
     *
     * @return Either a [TokenStorageError] on failure or the refresh token on success
     */
    suspend fun getRefreshToken(): Either<TokenStorageError, String>

    /**
     * Retrieves the expiration timestamp of the stored access token.
     *
     * @return Either a [TokenStorageError] on failure or the expiry timestamp on success
     */
    suspend fun getExpiry(): Either<TokenStorageError, Instant>

    /**
     * Clears all stored tokens from storage.
     * This operation should be performed during logout or when tokens are invalidated.
     *
     * @return Either a [TokenStorageError] on failure or Unit on success
     */
    suspend fun clearTokens(): Either<TokenStorageError, Unit>

}
