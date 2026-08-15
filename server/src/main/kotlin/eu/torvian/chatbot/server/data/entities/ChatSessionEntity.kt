package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a row from the 'chat_sessions' database table.
 * This is a direct mapping of the table columns for server-side data handling.
 *
 * @property id Unique identifier for the chat session.
 * @property name Name of the chat session.
 * @property createdAt Timestamp when the session was created.
 * @property updatedAt Timestamp when the session was last updated.
 * @property groupId Optional group ID for organizing chat sessions together.
 * @property agentRoleId Optional reference to the user-defined agent role selected for this session.
 *            Model/settings/tools are resolved from the role at turn time; null means no role selected.
 */
data class ChatSessionEntity(
    val id: Long,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val groupId: Long?,
    val agentRoleId: Long?
)
