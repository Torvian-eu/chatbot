package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table for the per-user device registry.
 *
 * Each row stores the client-provided UUID for a device together with a human-readable
 * label and timestamps that track when the device was created and last observed.
 *
 * @property userId Owning user of the registered device.
 * @property clientDeviceId Stable client-side UUID that identifies the device across sessions.
 * @property deviceName Human-readable label for the device as last known by the server.
 * @property createdAt Timestamp when the device was first recorded.
 * @property lastUsedAt Timestamp when the device was last used.
 */
object UserDevicesTable : LongIdTable("user_devices") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val clientDeviceId = varchar("client_device_id", 36)
    val deviceName = varchar("device_name", 255).nullable()
    val createdAt = long("created_at")
    val lastUsedAt = long("last_used_at")
    init {
        uniqueIndex("user_devices_user_id_client_device_id_unique", userId, clientDeviceId)
    }
}

