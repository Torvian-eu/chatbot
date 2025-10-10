package eu.torvian.chatbot.common.models.api.admin

import kotlinx.serialization.Serializable

/**
 * Request to update an existing user group.
 *
 * @property name New name for the group
 * @property description New description for the group
 */
@Serializable
data class UpdateUserGroupRequest(
    val name: String,
    val description: String? = null
)