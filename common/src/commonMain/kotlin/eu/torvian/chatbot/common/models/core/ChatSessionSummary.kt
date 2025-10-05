package eu.torvian.chatbot.common.models.core

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Represents a summary of a chat session, typically used for listing sessions
 * without loading all message details.
 * Used as a shared data model between frontend and backend.
 *
 * @property id Unique identifier for the session (Database PK).
 * @property name The name or title of the session.
 * @property createdAt Timestamp when the session was created.
 * @property updatedAt Timestamp when the session was last updated.
 * @property groupId Optional ID referencing a parent group session.
 * @property groupName Optional name of the group, if applicable.
 */
@Serializable
data class ChatSessionSummary(
    val id: Long,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val groupId: Long?,
    val groupName: String? = null
)
