package eu.torvian.chatbot.common.models.api.admin

import eu.torvian.chatbot.common.models.UserStatus
import kotlinx.serialization.Serializable

/**
 * API request payload to update a user's status.
 *
 * Used by admin endpoints to activate, disable, or unlock a user account.
 *
 * @property status The new status to apply to the user
 */
@Serializable
data class UpdateUserStatusRequest(
    val status: UserStatus
)
