package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.tables.RolesTable.description
import eu.torvian.chatbot.server.data.tables.RolesTable.name
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for user roles (e.g., Admin, Standard User).
 * 
 * This table defines the different roles that can be assigned to users.
 * Roles group together sets of permissions for easier management.
 * 
 * @property name Unique name of the role (e.g., "Admin", "StandardUser")
 * @property description Optional description explaining the role's purpose and capabilities
 */
object RolesTable : LongIdTable("roles") {
    val name = varchar("name", 50).uniqueIndex()
    val description = text("description").nullable()
}
