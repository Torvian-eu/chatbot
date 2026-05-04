package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.tables.UserSessionsTable.createdAt
import eu.torvian.chatbot.server.data.tables.UserSessionsTable.expiresAt
import eu.torvian.chatbot.server.data.tables.UserSessionsTable.ipAddress
import eu.torvian.chatbot.server.data.tables.UserSessionsTable.lastAccessed
import eu.torvian.chatbot.server.data.tables.UserSessionsTable.userId
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

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
 * @property ipAddress IP address of the client that created the session (nullable for proxy compatibility)
 */
object UserSessionsTable : LongIdTable("user_sessions") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")
    val lastAccessed = long("last_accessed")
    val ipAddress = varchar("ip_address", 45).nullable()
}
