package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table for tracking unacknowledged login attempts.
 *
 * This table records login attempts from unrecognized devices for security auditing.
 * When a user acknowledges these alerts, the device is promoted to the trusted devices table.
 */
object SecurityAuditTable : LongIdTable("security_audit") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val deviceId = varchar("device_id", 36)
    val ipAddress = varchar("ip_address", 45).nullable()
    val createdAt = long("created_at")
    val isAcknowledged = bool("is_acknowledged").default(false)

    init {
        index(false, userId)
        index(false, isAcknowledged)
    }
}
