package eu.torvian.chatbot.server.data.dao

import eu.torvian.chatbot.server.data.entities.UserTrustedIpEntity

/**
 * Data access operations for user/IP trust records.
 */
interface UserTrustedIpDao {
    /**
     * Retrieves the trust record for a specific user/IP pair.
     *
     * @param userId The owning user identifier.
     * @param ipAddress The textual client IP address.
     * @return The matching trust record, or null when the IP has not been seen before.
     */
    suspend fun getTrustedIp(userId: Long, ipAddress: String): UserTrustedIpEntity?

    /**
     * Inserts a new trust record.
     *
     * @param userId The owning user identifier.
     * @param ipAddress The textual client IP address.
     * @param isTrusted Whether the IP should be treated as trusted.
     * @param isAcknowledged Whether the user has acknowledged the record.
     * @param firstUsedAt Timestamp for the first observation.
     * @param lastUsedAt Timestamp for the most recent observation.
     * @return The persisted trust record.
     */
    suspend fun insertTrustedIp(
        userId: Long,
        ipAddress: String,
        isTrusted: Boolean,
        isAcknowledged: Boolean,
        firstUsedAt: Long,
        lastUsedAt: Long
    ): UserTrustedIpEntity

    /**
     * Updates the last-used timestamp for an existing trust record.
     *
     * @param id The trust record identifier.
     * @param lastUsedAt The new last-used timestamp.
     * @return True when a row was updated, false when the record no longer exists.
     */
    suspend fun updateLastUsedAt(id: Long, lastUsedAt: Long): Boolean

    /**
     * Marks all unacknowledged trust records for a user as acknowledged.
     *
     * @param userId The owning user identifier.
     * @return The number of rows updated.
     */
    suspend fun acknowledgeTrustedIps(userId: Long): Int

    /**
     * Retrieves all unacknowledged trust records for a user, sorted by lastUsedAt descending.
     *
     * @param userId The owning user identifier.
     * @return List of unacknowledged trust records.
     */
    suspend fun getUnacknowledgedByUserId(userId: Long): List<UserTrustedIpEntity>
}
