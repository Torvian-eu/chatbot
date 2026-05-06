package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a security audit record for an unacknowledged login attempt.
 *
 * @property id Unique identifier of the audit record.
 * @property userId Identifier of the owning user.
 * @property deviceId Client-side UUID
 * @property ipAddress IP address from which the login attempt was made.
 * @property createdAt Timestamp when the login attempt was recorded.
 * @property isAcknowledged Whether the user has acknowledged this alert.
 */
data class SecurityAuditEntity(
    val id: Long,
    val userId: Long,
    val deviceId: String,
    val ipAddress: String?,
    val createdAt: Instant,
    val isAcknowledged: Boolean = false
)
