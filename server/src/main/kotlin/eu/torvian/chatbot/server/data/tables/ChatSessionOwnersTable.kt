package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Links a chat session to its owning user.
 * 
 * This table establishes a one-to-one relationship between chat sessions
 * and their owners. Each session has exactly one owner, and ownership
 * determines who can access, modify, or delete the session.
 * 
 * @property sessionId Reference to the chat session being owned
 * @property userId Reference to the user who owns the session
 */
object ChatSessionOwnersTable : Table("chat_session_owners") {
    val sessionId = reference("session_id", ChatSessionTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    // session_id is primary key, ensuring 1 owner per session
    override val primaryKey = PrimaryKey(sessionId)
}
