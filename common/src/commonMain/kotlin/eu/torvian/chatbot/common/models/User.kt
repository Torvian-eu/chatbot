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
 * @property createdAt Timestamp when the user account was created
 * @property lastLogin Timestamp of the user's last successful login (nullable)
 */
@Serializable
data class User(
    val id: Long,
    val username: String,
    val email: String?,
    val createdAt: Instant,
    val lastLogin: Instant?
)
