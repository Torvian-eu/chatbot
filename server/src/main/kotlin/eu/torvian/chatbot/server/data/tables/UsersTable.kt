package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.tables.UsersTable.createdAt
import eu.torvian.chatbot.server.data.tables.UsersTable.email
import eu.torvian.chatbot.server.data.tables.UsersTable.lastLogin
import eu.torvian.chatbot.server.data.tables.UsersTable.passwordHash
import eu.torvian.chatbot.server.data.tables.UsersTable.requiresPasswordChange
import eu.torvian.chatbot.server.data.tables.UsersTable.status
import eu.torvian.chatbot.server.data.tables.UsersTable.updatedAt
import eu.torvian.chatbot.server.data.tables.UsersTable.username
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

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
 * @property requiresPasswordChange Whether the user must change their password upon next login
 */
object UsersTable : LongIdTable("users") {
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val email = varchar("email", 255).nullable().uniqueIndex()
    val status = enumerationByName<UserStatus>("status", length = 50).default(UserStatus.DISABLED)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val lastLogin = long("last_login").nullable()
    val requiresPasswordChange = bool("requires_password_change").default(false)
}
