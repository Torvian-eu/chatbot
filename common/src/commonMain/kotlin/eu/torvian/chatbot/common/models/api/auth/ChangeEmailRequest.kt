package eu.torvian.chatbot.common.models.api.auth

import kotlinx.serialization.Serializable

/**
 * Request body for changing the authenticated user's email address.
 *
 * Requires the current password for verification before changing the email.
 *
 * @property currentPassword The user's current password for verification
 * @property newEmail The new email address to set (must be valid and not already in use)
 */
@Serializable
data class ChangeEmailRequest(
    val currentPassword: String,
    val newEmail: String
)
