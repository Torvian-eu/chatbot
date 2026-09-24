package eu.torvian.chatbot.common.models.api.admin

import kotlinx.serialization.Serializable

/**
 * Request body for administrator-initiated user account creation.
 *
 * @property username Unique username for the new user account
 * @property password Plaintext initial password (will be hashed server-side)
 * @property email Optional email address for the user (must be unique if provided)
 * @property requiresPasswordChange Whether the user must set a new password on first login (default: true)
 */
@Serializable
data class CreateUserRequest(
    val username: String,
    val password: String,
    val email: String? = null,
    val requiresPasswordChange: Boolean = true
)
