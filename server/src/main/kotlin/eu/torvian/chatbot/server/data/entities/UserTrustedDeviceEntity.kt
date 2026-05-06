package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a trusted device associated with a user account.
 *
 * @property id Unique identifier of the trust record.
 * @property userId Identifier of the owning user.
 * @property deviceId Client-side UUID that persists across logouts.
 * @property lastIpAddress Last IP address observed for this device (for context).
 * @property firstSeenAt Timestamp when the device was first observed.
 * @property lastUsedAt Timestamp when the device was most recently used.
 */
data class UserTrustedDeviceEntity(
    val id: Long,
    val userId: Long,
    val deviceId: String,
    val lastIpAddress: String?,
    val firstSeenAt: Instant,
    val lastUsedAt: Instant
)
