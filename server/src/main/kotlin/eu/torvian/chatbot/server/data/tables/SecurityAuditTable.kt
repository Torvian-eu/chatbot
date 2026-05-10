package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.security.SecurityAuditStatus
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table for tracking security audit records for unrecognized device logins.
 *
 * This table records login attempts from unrecognized devices for security auditing.
 * Each record has a status indicating whether it is pending, trusted, or dismissed.
 * Records are preserved for audit purposes and are never deleted.
 */
object SecurityAuditTable : LongIdTable("security_audit") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val deviceId = varchar("device_id", 36)
    val ipAddress = varchar("ip_address", 45).nullable()
    val createdAt = long("created_at")
    val status = varchar("status", 20).default(SecurityAuditStatus.PENDING.name)
    val resolvedAt = long("resolved_at").nullable()

    init {
        index(false, userId)
        index(false, status)
    }
}
