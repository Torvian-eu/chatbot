package eu.torvian.chatbot.server.data.entities

import eu.torvian.chatbot.common.security.SecurityAuditStatus
import kotlin.time.Instant

/**
 * Represents a security audit record for an unrecognized device login attempt.
 *
 * @property id Unique identifier of the audit record.
 * @property userId Identifier of the owning user.
 * @property deviceId Client-side UUID of the device that was used for login.
 * @property ipAddress IP address from which the login attempt was made.
 * @property createdAt Timestamp when the login attempt was recorded.
 * @property status Current status of the alert (PENDING, TRUSTED, or DISMISSED).
 * @property resolvedAt Timestamp when the user resolved the alert, or null if still pending.
 */
data class SecurityAuditEntity(
    val id: Long,
    val userId: Long,
    val deviceId: String,
    val ipAddress: String?,
    val createdAt: Instant,
    val status: SecurityAuditStatus = SecurityAuditStatus.PENDING,
    val resolvedAt: Instant? = null
)
