package eu.torvian.chatbot.common.models.api.auth

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Public device summary returned to authenticated users when listing their trusted devices.
 *
 * @property deviceId Unique identifier of the device (client-side UUID).
 * @property lastIpAddress Last IP address observed for this device, if available.
 * @property firstSeenAt Timestamp when the device was first trusted.
 * @property lastUsedAt Timestamp when the device was most recently used.
 */
@Serializable
data class UserTrustedDeviceInfo(
    val deviceId: String,
    val lastIpAddress: String?,
    val firstSeenAt: Instant,
    val lastUsedAt: Instant
)
