package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Exposed table definition to link users to groups.
 * 
 * This is a many-to-many association table that defines which groups
 * each user belongs to. Users can belong to multiple groups, and each
 * group can contain multiple users.
 * 
 * All users are automatically added to the special "All Users" group
 * during registration and cannot be removed from it.
 * 
 * @property userId Reference to the user who is a member of the group
 * @property groupId Reference to the group the user belongs to
 */
object UserGroupMembershipsTable : Table("user_group_memberships") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val groupId = reference("group_id", UserGroupsTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(userId, groupId)
}
