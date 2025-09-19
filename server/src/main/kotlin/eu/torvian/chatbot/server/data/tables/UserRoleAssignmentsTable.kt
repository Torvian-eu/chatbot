package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Exposed table definition to assign roles to users.
 * 
 * This is a many-to-many association table that defines which roles
 * are assigned to each user. Users can have multiple roles, and each
 * role can be assigned to multiple users.
 * 
 * @property userId Reference to the user being assigned a role
 * @property roleId Reference to the role being assigned to the user
 * @property assignedAt Timestamp when the role was assigned (epoch milliseconds)
 */
object UserRoleAssignmentsTable : Table("user_role_assignments") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val roleId = reference("role_id", RolesTable, onDelete = ReferenceOption.CASCADE)
    val assignedAt = long("assigned_at").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(userId, roleId)
}
