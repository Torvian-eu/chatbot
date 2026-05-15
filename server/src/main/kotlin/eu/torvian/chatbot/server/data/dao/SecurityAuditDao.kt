package eu.torvian.chatbot.server.data.dao

import eu.torvian.chatbot.common.security.SecurityAuditStatus
import eu.torvian.chatbot.server.data.entities.SecurityAuditEntity

/**
 * Data access operations for security audit records.
 */
interface SecurityAuditDao {
    /**
     * Inserts a new security audit record for an unrecognized login attempt.
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
     * Retrieves all pending security audit records for a user.
     *
     * @param userId The owning user identifier.
     * @return List of pending audit records.
     */
    suspend fun getUnacknowledgedByUserId(userId: Long): List<SecurityAuditEntity>

    /**
     * Retrieves all pending security audit records for a user filtered by device ID.
     *
     * @param userId The owning user identifier.
     * @param deviceId The client-side UUID of the device to filter by.
     * @return List of pending audit records for the specified device.
     */
    suspend fun getUnacknowledgedByUserIdAndDeviceId(userId: Long, deviceId: String): List<SecurityAuditEntity>

    /**
     * Updates the status of a specific audit record.
     *
     * @param id The audit record identifier.
     * @param status The new status to set.
     * @param resolvedAt The timestamp when the record was resolved.
     * @return The number of rows updated.
     */
    suspend fun updateStatus(id: Long, status: SecurityAuditStatus, resolvedAt: Long): Int

    /**
     * Retrieves a single audit record by its ID.
     *
     * @param id The audit record identifier.
     * @return The audit record, or null if not found.
     */
    suspend fun getAuditRecordById(id: Long): SecurityAuditEntity?
}
