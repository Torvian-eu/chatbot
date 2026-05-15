package eu.torvian.chatbot.server.data.dao

import eu.torvian.chatbot.server.data.entities.DeviceVerificationTokenEntity

/**
 * Data access operations for device verification tokens.
 *
 * This DAO handles the lifecycle of verification tokens used for email-based
 * device trust promotion. Tokens are single-use and expire after a set duration.
 */
interface DeviceVerificationTokenDao {
    /**
     * Creates a new verification token for a user/device pair.
     *
     * @param userId The owning user identifier.
     * @param deviceId The client-side UUID of the device to verify.
     * @param token The cryptographically secure token string.
     * @param expiresAt Timestamp when the token expires.
     * @param createdAt Timestamp when the token was created.
     * @return The persisted verification token entity.
     */
    suspend fun createToken(
        userId: Long,
        deviceId: String,
        token: String,
        expiresAt: Long,
        createdAt: Long
    ): DeviceVerificationTokenEntity

    /**
     * Finds a verification token by its token string.
     *
     * @param token The token string to search for.
     * @return The matching token entity, or null if not found.
     */
    suspend fun findToken(token: String): DeviceVerificationTokenEntity?

    /**
     * Deletes a verification token (typically after successful use).
     *
     * @param token The token string to delete.
     * @return The number of rows deleted (0 or 1).
     */
    suspend fun deleteToken(token: String): Int

    /**
     * Gets the creation timestamp of the most recent token for a user/device pair.
     *
     * This is used for rate limiting - to check when the last verification email
     * was sent for this specific user and device.
     *
     * @param userId The owning user identifier.
     * @param deviceId The client-side UUID of the device.
     * @return The creation timestamp of the last token, or null if no token exists.
     */
    suspend fun getLastTokenCreatedAt(userId: Long, deviceId: String): Long?
}
