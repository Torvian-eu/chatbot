package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

/**
 * Exposed table definition for active user sessions.
 * 
 * This table tracks active authentication sessions for users. The session ID
 * is stored in JWT tokens for later verification. For security reasons, 
 * JWT tokens themselves are not stored in the database.
 * 
 * @property userId Reference to the user who owns this session
 * @property expiresAt Timestamp when the session expires (epoch milliseconds)
 * @property createdAt Timestamp when the session was created (epoch milliseconds)
 * @property lastAccessed Timestamp when the session was last accessed (epoch milliseconds)
 */
object UserSessionsTable : LongIdTable("user_sessions") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val expiresAt = long("expires_at")
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val lastAccessed = long("last_accessed").default(System.currentTimeMillis())
}
