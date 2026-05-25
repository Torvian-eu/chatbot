package eu.torvian.chatbot.server.data.dao

import eu.torvian.chatbot.server.data.entities.UserDeviceEntity

/**
 * Data access operations for the per-user device registry.
 */
interface UserDeviceDao {
    /**
     * Looks up a registered device by the owning user and client-side UUID.
     *
     * @param userId Owning user identifier.
     * @param clientDeviceId Stable client-side UUID used by the device.
     * @return The matching device record, or null when the user has not registered the device yet.
     */
    suspend fun getDeviceByClientId(userId: Long, clientDeviceId: String): UserDeviceEntity?

    /**
     * Registers a new device for the given user.
     *
     * @param userId Owning user identifier.
     * @param clientDeviceId Stable client-side UUID used by the device.
     * @param name Human-readable label to store for the device.
     * @return The persisted device record.
     */
    suspend fun insertDevice(userId: Long, clientDeviceId: String, name: String?): UserDeviceEntity

    /**
     * Updates the last-used timestamp for an existing device record.
     *
     * @param id Device record identifier.
     * @param lastUsedAt New last-used timestamp in epoch milliseconds.
     * @return True when a row was updated, false when the record no longer exists.
     */
    suspend fun updateDeviceUsage(id: Long, lastUsedAt: Long): Boolean
}

