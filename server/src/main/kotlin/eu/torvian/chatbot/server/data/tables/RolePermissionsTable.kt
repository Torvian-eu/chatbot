package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Exposed table definition to link roles to specific permissions.
 * 
 * This is a many-to-many association table that defines which permissions
 * are granted to each role. When a user is assigned a role, they inherit
 * all permissions associated with that role.
 * 
 * @property roleId Reference to the role being granted permissions
 * @property permissionId Reference to the permission being granted to the role
 */
object RolePermissionsTable : Table("role_permissions") {
    val roleId = reference("role_id", RolesTable, onDelete = ReferenceOption.CASCADE)
    val permissionId = reference("permission_id", PermissionsTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(roleId, permissionId)
}
