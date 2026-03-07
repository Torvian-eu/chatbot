package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a row from the 'user_sessions' database table.
 * This is a direct mapping of the table columns for server-side data handling.
 *
 * @property id Unique identifier for the user session.
 * @property userId ID of the user who owns this session.
 * @property expiresAt Timestamp when the session expires.
 * @property createdAt Timestamp when the session was created.
 * @property lastAccessed Timestamp when the session was last accessed.
 */
data class UserSessionEntity(
    val id: Long,
    val userId: Long,
    val expiresAt: Instant,
    val createdAt: Instant,
    val lastAccessed: Instant
)
