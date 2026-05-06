package eu.torvian.chatbot.server.data.dao

import eu.torvian.chatbot.server.data.entities.UserTrustedDeviceEntity

/**
 * Data access operations for user/device trust records (whitelist).
 *
 * This interface handles only the CRUD operations for the trusted devices whitelist.
 * It does not handle acknowledgements - that is managed by SecurityAuditDao.
 */
interface UserTrustedDeviceDao {
    /**
     * Retrieves the trust record for a specific user/device pair.
     *
     * @param userId The owning user identifier.
     * @param deviceId The client-side UUID of the device.
     * @return The matching trust record, or null when the device has not been seen before.
     */
    suspend fun getTrustedDevice(userId: Long, deviceId: String): UserTrustedDeviceEntity?

    /**
     * Inserts a new trust record.
     *
     * @param userId The owning user identifier.
     * @param deviceId The client-side UUID of the device.
     * @param ipAddress The IP address from which the device first connected.
     * @param firstSeenAt Timestamp for the first observation.
     * @param lastUsedAt Timestamp for the most recent observation.
     * @return The persisted trust record.
     */
    suspend fun insertTrustedDevice(
        userId: Long,
        deviceId: String,
        ipAddress: String?,
        firstSeenAt: Long,
        lastUsedAt: Long
    ): UserTrustedDeviceEntity

    /**
     * Updates the last-used timestamp and IP address for an existing trust record.
     *
     * @param id The trust record identifier.
     * @param lastUsedAt The new last-used timestamp.
     * @param lastIpAddress The new last IP address.
     * @return True when a row was updated, false when the record no longer exists.
     */
    suspend fun updateLastUsedAt(id: Long, lastUsedAt: Long, lastIpAddress: String?): Boolean

    /**
     * Counts the number of trusted devices for a specific user.
     *
     * This is used for Trust on First Use (TOFU) to determine if this is the user's
     * first device login - in which case the device is automatically trusted.
     *
     * @param userId The owning user identifier.
     * @return The count of trusted devices for the user.
     */
    suspend fun getTrustedDevicesCount(userId: Long): Int

    /**
     * Retrieves all trusted devices for a specific user.
     *
     * @param userId The owning user identifier.
     * @return The list of trusted device entities for the user.
     */
    suspend fun getTrustedDevices(userId: Long): List<UserTrustedDeviceEntity>

    /**
     * Deletes a specific trusted device for a user.
     *
     * @param userId The owning user identifier.
     * @param deviceId The client-side UUID of the device to remove.
     * @return The number of rows deleted (0 or 1).
     */
    suspend fun deleteTrustedDevice(userId: Long, deviceId: String): Int
}
