package eu.torvian.chatbot.common.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Represents a user account for API communication.
 * Used as a shared data model between frontend and backend API communication.
 *
 * This model excludes sensitive information like password hashes and is safe
 * to send to clients.
 *
 * @property id Unique identifier for the user account
 * @property username Unique username for the user account
 * @property email Optional email address for the user
 * @property status Current operational status of the account (e.g., ACTIVE, DISABLED, LOCKED)
 * @property createdAt Timestamp when the user account was created
 * @property lastLogin Timestamp of the user's last successful login (nullable)
 * @property requiresPasswordChange Whether the user must change their password upon next login
 */
@Serializable
data class User(
    val id: Long,
    val username: String,
    val email: String?,
    val status: UserStatus,
    val createdAt: Instant,
    val lastLogin: Instant?,
    val requiresPasswordChange: Boolean = false
)
