package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table for device trust records used by the authentication service.
 *
 * Each row tracks one user/device pair. The deviceId is a client-side UUID that persists
 * across logouts, enabling device-based trust rather than IP-based trust.
 * The IP address is stored for context in security alerts but is not the primary key.
 */
object UserTrustedDevicesTable : LongIdTable("user_trusted_devices") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val deviceId = varchar("device_id", 36) // UUID format
    val lastIpAddress = varchar("last_ip_address", 45).nullable()
    val firstSeenAt = long("first_seen_at")
    val lastUsedAt = long("last_used_at")

    init {
        uniqueIndex("user_trusted_devices_user_id_device_id_unique", userId, deviceId)
    }
}
