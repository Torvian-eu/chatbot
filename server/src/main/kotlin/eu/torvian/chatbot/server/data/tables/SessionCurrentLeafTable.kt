package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.tables.SessionCurrentLeafTable.messageId
import eu.torvian.chatbot.server.data.tables.SessionCurrentLeafTable.sessionId
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for session current leaf message links.
 * This table breaks the circular dependency between ChatSessionTable and ChatMessageTable
 * by storing the current leaf message reference in a separate table.
 *
 * A leaf message is the last message in the active branch of a session.
 *
 * @property sessionId Reference to the chat session (primary key)
 * @property messageId Reference to the current leaf message for the session
 */
object SessionCurrentLeafTable : Table("session_current_leaf") {
    val sessionId = reference(
        "session_id",
        ChatSessionTable,
        onDelete = ReferenceOption.CASCADE // If session deleted, link is deleted
    )

    // This UNIQUE constraint ensures a message can appear at most once
    val messageId = reference(
        "message_id",
        ChatMessageTable,
        onDelete = ReferenceOption.CASCADE // If message deleted, link is deleted
    ).uniqueIndex()

    // Primary key on sessionId enforces one leaf per session
    override val primaryKey = PrimaryKey(sessionId)
}
