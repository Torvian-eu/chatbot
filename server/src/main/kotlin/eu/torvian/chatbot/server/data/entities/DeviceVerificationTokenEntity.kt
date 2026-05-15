package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a device verification token for email-based device trust.
 *
 * @property id Unique identifier of the token record.
 * @property userId Identifier of the user who requested the verification.
 * @property deviceId Client-side UUID of the device to be trusted.
 * @property token The cryptographically secure token sent via email.
 * @property expiresAt Timestamp when the token expires.
 * @property createdAt Timestamp when the token was created.
 */
data class DeviceVerificationTokenEntity(
    val id: Long,
    val userId: Long,
    val deviceId: String,
    val token: String,
    val expiresAt: Instant,
    val createdAt: Instant
)
