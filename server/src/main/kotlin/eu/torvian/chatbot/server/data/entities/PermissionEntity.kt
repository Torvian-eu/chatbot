package eu.torvian.chatbot.server.data.entities

/**
 * Represents a row from the 'permissions' database table.
 * This is a direct mapping of the table columns for server-side data handling.
 *
 * @property id Unique identifier for the permission.
 * @property action The action being permitted (e.g., "manage", "create", "delete").
 * @property subject The subject/resource the action applies to (e.g., "users", "public_provider").
 */
data class PermissionEntity(
    val id: Long,
    val action: String,
    val subject: String
)

