package eu.torvian.chatbot.common.models.api.auth

import kotlinx.serialization.Serializable

/**
 * Request body for changing the authenticated user's password.
 *
 * Requires the current password for verification before setting a new one.
 *
 * @property currentPassword The user's current password for verification
 * @property newPassword The new password to set (must meet strength requirements)
 */
@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)
