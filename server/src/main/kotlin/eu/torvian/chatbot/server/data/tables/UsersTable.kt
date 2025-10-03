package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.UserStatus
import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * Exposed table definition for user accounts.
 *
 * This table stores user account information including authentication credentials
 * and basic profile data. Passwords are stored as secure hashes, never in plaintext.
 *
 * @property username Unique username for the user account
 * @property passwordHash Securely hashed password (using BCrypt or similar)
 * @property email Optional email address for the user (unique if provided)
 * @property status Operational status of the user account
 * @property createdAt Timestamp when the user account was created (epoch milliseconds)
 * @property updatedAt Timestamp when the user account was last updated (epoch milliseconds)
 * @property lastLogin Timestamp of the user's last successful login (nullable, epoch milliseconds)
 */
object UsersTable : LongIdTable("users") {
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val email = varchar("email", 255).nullable().uniqueIndex()
    val status = enumerationByName<UserStatus>("status", length = 50).default(UserStatus.DISABLED)
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
    val lastLogin = long("last_login").nullable()
}
