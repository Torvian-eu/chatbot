package eu.torvian.chatbot.server.data.dao

import eu.torvian.chatbot.server.data.entities.SecurityAuditEntity

/**
 * Data access operations for security audit records.
 */
interface SecurityAuditDao {
    /**
     * Inserts a new security audit record for an unacknowledged login attempt.
     *
     * @param userId The owning user identifier.
     * @param deviceId The client-side UUID of the device (mandatory).
     * @param ipAddress The IP address from which the login attempt was made.
     * @param createdAt Timestamp when the login attempt was recorded.
     * @return The persisted audit record.
     */
    suspend fun insertAuditRecord(
        userId: Long,
        deviceId: String,
        ipAddress: String?,
        createdAt: Long
    ): SecurityAuditEntity

    /**
     * Retrieves all unacknowledged security audit records for a user.
     *
     * @param userId The owning user identifier.
     * @return List of unacknowledged audit records.
     */
    suspend fun getUnacknowledgedByUserId(userId: Long): List<SecurityAuditEntity>

    /**
     * Marks all unacknowledged security audit records for a user as acknowledged.
     *
     * @param userId The owning user identifier.
     * @return The number of rows updated.
     */
    suspend fun acknowledgeAllByUserId(userId: Long): Int

    /**
     * Gets unique device IDs from unacknowledged audit records for a user.
     *
     * @param userId The owning user identifier.
     * @return Set of unique device IDs.
     */
    suspend fun getUniqueDeviceIdsFromUnacknowledged(userId: Long): Set<String>
}
