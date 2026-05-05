package eu.torvian.chatbot.common.models.api.auth

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Represents an unacknowledged security alert for an unrecognized IP login.
 *
 * This model is returned by the security-alerts endpoint to provide detailed
 * information about unrecognized login attempts that require user acknowledgment.
 *
 * @property id Unique identifier of the trust record.
 * @property ipAddress The IP address that was used for an unrecognized login.
 * @property firstSeenAt Timestamp when this IP was first observed for this user.
 * @property lastSeenAt Timestamp when this IP was most recently observed.
 */
@Serializable
data class UserSecurityAlert(
    val id: Long,
    val ipAddress: String,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant
)
