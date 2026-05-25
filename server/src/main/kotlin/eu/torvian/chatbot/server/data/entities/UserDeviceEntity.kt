package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a row in the device registry for a specific user.
 *
 * @property id Unique identifier of the device record.
 * @property userId Owning user identifier.
 * @property clientDeviceId Stable client-side UUID used to correlate devices across sessions.
 * @property deviceName Latest human-readable label associated with the device.
 * @property createdAt Timestamp when the device was first recorded.
 * @property lastUsedAt Timestamp when the device was last seen.
 */
data class UserDeviceEntity(
    val id: Long,
    val userId: Long,
    val clientDeviceId: String,
    val deviceName: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant
)

