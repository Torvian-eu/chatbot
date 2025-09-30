package eu.torvian.chatbot.server.data.entities

/**
 * Represents a row from the 'role_permissions' database table.
 * This is a direct mapping of the table columns for server-side data handling.
 * Links roles to their permissions in a many-to-many relationship.
 *
 * @property roleId The ID of the role that has this permission.
 * @property permissionId The ID of the permission granted to the role.
 */
data class RolePermissionEntity(
    val roleId: Long,
    val permissionId: Long
)
