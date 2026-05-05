package eu.torvian.chatbot.common.models.api.auth

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Public session summary returned to authenticated users when listing their active sessions.
 *
 * @property sessionId Unique identifier of the session.
 * @property ipAddress IP address captured when the session was created or refreshed, if available.
 * @property createdAt Timestamp when the session was created.
 * @property lastAccessed Timestamp when the session was last used.
 * @property expiresAt Timestamp when the session expires.
 * @property isCurrentSession Indicates whether this session matches the session used by the current request.
 */
@Serializable
data class UserSessionInfo(
    val sessionId: Long,
    val ipAddress: String?,
    val createdAt: Instant,
    val lastAccessed: Instant,
    val expiresAt: Instant,
    val isCurrentSession: Boolean
)
