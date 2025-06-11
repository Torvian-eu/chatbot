package eu.torvian.chatbot.server.data.entities

/**
 * Represents a row from the 'session_current_leaf' database table.
 * This is a direct mapping of the table that manages the relationship
 * between sessions and their current leaf messages, breaking the
 * circular dependency between sessions and messages.
 *
 * @property sessionId The ID of the session this leaf belongs to (primary key)
 * @property messageId The ID of the message that is the current leaf for this session
 */
data class SessionCurrentLeafEntity(
    val sessionId: Long,
    val messageId: Long
)
