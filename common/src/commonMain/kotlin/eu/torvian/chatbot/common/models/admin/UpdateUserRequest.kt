package eu.torvian.chatbot.common.models.admin

import kotlinx.serialization.Serializable

/**
 * Request body for updating a user's profile (admin only).
 *
 * @property username New username (must be unique)
 * @property email New email address (must be unique if provided)
 */
@Serializable
data class UpdateUserRequest(
    val username: String,
    val email: String? = null
)
