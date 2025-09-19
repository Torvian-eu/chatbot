package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * Exposed table definition for granular permissions (e.g., create_public_provider, manage_users).
 * 
 * This table defines specific permissions that can be granted to roles.
 * Permissions follow an action-subject pattern for fine-grained access control.
 * 
 * @property action The action being permitted (e.g., "create", "update", "delete", "manage")
 * @property subject The subject/resource the action applies to (e.g., "public_provider", "users", "models")
 */
object PermissionsTable : LongIdTable("permissions") {
    val action = varchar("action", 100)
    val subject = varchar("subject", 100)

    init {
        // Unique constraint for (action, subject) pairs to prevent duplicate permissions
        uniqueIndex(action, subject)
    }
}
