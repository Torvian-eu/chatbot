package eu.torvian.chatbot.common.models.api.admin

import kotlinx.serialization.Serializable

/**
 * API request payload to update a user's password change required flag.
 *
 * Used by admin endpoints to force or clear the password change requirement.
 *
 * @property requiresPasswordChange Whether the user must change their password on next login
 */
@Serializable
data class UpdatePasswordChangeRequiredRequest(
    val requiresPasswordChange: Boolean
)

