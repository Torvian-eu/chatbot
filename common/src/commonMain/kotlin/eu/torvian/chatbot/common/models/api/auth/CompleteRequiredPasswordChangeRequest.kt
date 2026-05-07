package eu.torvian.chatbot.common.models.api.auth

import kotlinx.serialization.Serializable
import eu.torvian.chatbot.common.models.user.User

/**
 * Request body for completing a server-required password change.
 *
 * This endpoint is used when a user is forced to change their password
 * (when [User.requiresPasswordChange] is true).
 * Unlike normal password change, this does not require the current password.
 *
 * @property newPassword The new password to set (must meet strength requirements)
 */
@Serializable
data class CompleteRequiredPasswordChangeRequest(
    val newPassword: String
)
