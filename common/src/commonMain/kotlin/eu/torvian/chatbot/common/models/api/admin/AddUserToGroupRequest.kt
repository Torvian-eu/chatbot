package eu.torvian.chatbot.common.models.api.admin

import kotlinx.serialization.Serializable

/**
 * Request to add a user to a group.
 *
 * @property userId The ID of the user to add to the group
 */
@Serializable
data class AddUserToGroupRequest(
    val userId: Long
)