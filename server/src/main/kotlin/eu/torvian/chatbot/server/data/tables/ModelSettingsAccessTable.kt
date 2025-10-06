package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Defines which user groups can access a ModelSettings profile.
 * 
 * This table enables group-based sharing of model settings profiles. When a settings
 * profile is linked to the special "All Users" group, it becomes publicly accessible
 * to all users in the system.
 * 
 * @property settingsId Reference to the model settings profile being shared
 * @property userGroupId Reference to the user group that has access to the settings
 * @property accessMode String representation of the access mode (e.g. "read", "write")
 */
object ModelSettingsAccessTable : Table("model_settings_access") {
    val settingsId = reference("settings_id", ModelSettingsTable, onDelete = ReferenceOption.CASCADE)
    val userGroupId = reference("user_group_id", UserGroupsTable, onDelete = ReferenceOption.CASCADE)
    val accessMode = varchar("access_mode", 50)

    // Composite primary key to allow multiple access modes per group
    override val primaryKey = PrimaryKey(settingsId, userGroupId, accessMode)

    // Index for efficient lookups by settings and group
    init {
        index(false, settingsId, userGroupId)
    }
}
