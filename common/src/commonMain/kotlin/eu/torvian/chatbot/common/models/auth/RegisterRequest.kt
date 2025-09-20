package eu.torvian.chatbot.common.models.auth

import kotlinx.serialization.Serializable

/**
 * Request body for user registration.
 *
 * @property username Unique username for the new user account
 * @property password Plaintext password (will be hashed server-side)
 * @property email Optional email address for the user (must be unique if provided)
 */
@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String? = null
)
