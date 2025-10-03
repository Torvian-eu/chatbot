package eu.torvian.chatbot.common.models.admin

import kotlinx.serialization.Serializable

/**
 * Request model for updating an existing role.
 *
 * This model is used when administrators want to update a role's information.
 * System roles (Admin, StandardUser) are protected from name changes but
 * their descriptions can be updated.
 *
 * @property name The new name for the role (required, max 50 characters)
 * @property description The new description for the role
 */
@Serializable
data class UpdateRoleRequest(
    val name: String,
    val description: String? = null
)
