package eu.torvian.chatbot.common.models.api.admin

import kotlinx.serialization.Serializable

/**
 * Request to create a new user group.
 *
 * @property name Unique name for the group (required)
 * @property description Optional description of the group's purpose
 */
@Serializable
data class CreateUserGroupRequest(
    val name: String,
    val description: String? = null
)