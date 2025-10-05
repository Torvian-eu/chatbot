package eu.torvian.chatbot.common.models.user

import kotlinx.serialization.Serializable

/**
 * Represents a role in the system.
 *
 * Roles group together sets of permissions for easier management.
 * Users can have multiple roles, and inherit all permissions from those roles.
 *
 * @property id Unique identifier for the role
 * @property name Unique name of the role (e.g., "Admin", "StandardUser")
 * @property description Optional description explaining the role's purpose and capabilities
 */
@Serializable
data class Role(
    val id: Long,
    val name: String,
    val description: String?
)
