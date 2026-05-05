package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a trusted or pending-trust IP address associated with a user account.
 *
 * @property id Unique identifier of the trust record.
 * @property userId Identifier of the owning user.
 * @property ipAddress Client IP address in textual form.
 * @property isTrusted Whether the IP is allowed to authenticate.
 * @property isAcknowledged Whether the user has dismissed the security alert for this IP.
 * @property firstUsedAt Timestamp when the IP was first observed.
 * @property lastUsedAt Timestamp when the IP was most recently observed.
 */
data class UserTrustedIpEntity(
    val id: Long,
    val userId: Long,
    val ipAddress: String,
    val isTrusted: Boolean,
    val isAcknowledged: Boolean,
    val firstUsedAt: Instant,
    val lastUsedAt: Instant
)

