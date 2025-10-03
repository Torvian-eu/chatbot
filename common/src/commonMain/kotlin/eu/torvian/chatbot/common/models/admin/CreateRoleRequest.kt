package eu.torvian.chatbot.common.models.admin

import kotlinx.serialization.Serializable

/**
 * Request model for creating a new role.
 *
 * This model is used when administrators want to create a new role in the system.
 * The role name must be unique and cannot conflict with system role names.
 *
 * @property name The unique name for the new role (required, max 50 characters)
 * @property description Optional description explaining the role's purpose
 */
@Serializable
data class CreateRoleRequest(
    val name: String,
    val description: String? = null
)
