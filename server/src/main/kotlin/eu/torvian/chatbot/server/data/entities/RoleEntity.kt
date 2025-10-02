package eu.torvian.chatbot.server.data.entities

/**
 * Represents a row from the 'roles' database table.
 * This is a direct mapping of the table columns for server-side data handling.
 *
 * @property id Unique identifier for the role.
 * @property name Unique name of the role (e.g., "Admin", "StandardUser").
 * @property description Optional description explaining the role's purpose and capabilities.
 */
data class RoleEntity(
    val id: Long,
    val name: String,
    val description: String?
)

