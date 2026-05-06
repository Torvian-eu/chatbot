package eu.torvian.chatbot.common.models.api.auth

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Represents an unacknowledged security alert for an unrecognized device login.
 *
 * This model is returned by the security-alerts endpoint to provide detailed
 * information about unrecognized login attempts that require user acknowledgment.
 *
 * @property id Unique identifier of the security alert.
 * @property deviceId The device ID that was used for an unrecognized login.
 * @property ipAddress The IP address that was used for an unrecognized login (for context).
 * @property firstSeenAt Timestamp when this device was first observed for this user.
 * @property lastSeenAt Timestamp when this device was most recently observed.
 */
@Serializable
data class UserSecurityAlert(
    val id: Long,
    val deviceId: String,
    val ipAddress: String?,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant
)
