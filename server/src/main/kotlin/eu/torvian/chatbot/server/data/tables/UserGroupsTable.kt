package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.tables.UserGroupsTable.description
import eu.torvian.chatbot.server.data.tables.UserGroupsTable.name
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for user-defined groups (e.g., 'Team A', 'All Users').
 * 
 * This table defines groups that can be used for sharing resources between users.
 * A special group 'All Users' will be created during initial setup and cannot be deleted.
 * All users are automatically added to the 'All Users' group to enable public resource sharing.
 * 
 * @property name Unique name of the group (e.g., "All Users", "Team A", "Developers")
 * @property description Optional description explaining the group's purpose
 */
object UserGroupsTable : LongIdTable("user_groups") {
    val name = varchar("name", 255).uniqueIndex()
    val description = text("description").nullable()
}
