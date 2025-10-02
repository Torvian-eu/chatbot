package eu.torvian.chatbot.common.models.admin

import kotlinx.serialization.Serializable

/**
 * Request body for changing a user's password (admin only).
 *
 * @property newPassword The new plaintext password (will be hashed server-side)
 */
@Serializable
data class ChangePasswordRequest(
    val newPassword: String
)
