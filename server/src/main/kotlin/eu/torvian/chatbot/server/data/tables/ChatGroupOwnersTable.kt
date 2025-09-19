package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Links a chat group to its owning user.
 * 
 * This table establishes a one-to-one relationship between chat groups
 * and their owners. Each group has exactly one owner, and ownership
 * determines who can access, modify, or delete the group.
 * 
 * @property groupId Reference to the chat group being owned
 * @property userId Reference to the user who owns the group
 */
object ChatGroupOwnersTable : Table("chat_group_owners") {
    val groupId = reference("group_id", ChatGroupTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    // group_id is primary key, ensuring 1 owner per group
    override val primaryKey = PrimaryKey(groupId)
}
