package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.tables.ModelSettingsOwnersTable.settingsId
import eu.torvian.chatbot.server.data.tables.ModelSettingsOwnersTable.userId
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Links model settings to their owning user.
 * 
 * This table establishes a one-to-one relationship between model settings profiles
 * and their owners. Each settings profile has exactly one owner, and ownership
 * determines who can access, modify, or delete the settings.
 * 
 * @property settingsId Reference to the model settings profile being owned
 * @property userId Reference to the user who owns the settings profile
 */
object ModelSettingsOwnersTable : Table("model_settings_owners") {
    val settingsId = reference("settings_id", ModelSettingsTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    // settings_id is primary key, ensuring 1 owner per settings profile
    override val primaryKey = PrimaryKey(settingsId)
}
