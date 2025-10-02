package eu.torvian.chatbot.server.data.entities

import eu.torvian.chatbot.common.api.PermissionSpec

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
) {
    // Secondary constructor overload: create a PermissionEntity from an id and a PermissionSpec
    constructor(id: Long, permissionSpec: PermissionSpec) : this(id, permissionSpec.action, permissionSpec.subject)
}
