package eu.torvian.chatbot.server.data.entities

import eu.torvian.chatbot.common.models.UserStatus
import kotlinx.datetime.Instant

/**
 * Represents a row from the 'users' database table.
 * This is a direct mapping of the table columns for server-side data handling.
 *
 * @property id Unique identifier for the user account.
 * @property username Unique username for the user account.
 * @property passwordHash Securely hashed password (never stored in plaintext).
 * @property email Optional email address for the user (unique if provided).
 * @property status Operational status of the user account
 * @property createdAt Timestamp when the user account was created.
 * @property updatedAt Timestamp when the user account was last updated.
 * @property lastLogin Timestamp of the user's last successful login (nullable).
 */
data class UserEntity(
    val id: Long,
    val username: String,
    val passwordHash: String,
    val email: String?,
    val status: UserStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastLogin: Instant?
)
