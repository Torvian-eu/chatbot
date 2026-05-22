package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table for user preferences with global and device-specific scopes.
 *
 * A preference belongs to a user and can either apply to every device or to a single
 * registered device via the optional device reference.
 *
 * The [scopeId] column stores the scope identifier: "GLOBAL" for global settings,
 * or the clientDeviceId (UUID string) for device-specific settings. This enables
 * reliable upsert behavior since the unique index now contains only non-nullable columns.
 *
 * @property userId Owning user of the preference.
 * @property deviceId Optional reference to the device that owns a device-scoped preference.
 * @property scopeId Scope identifier: "GLOBAL" for global, or clientDeviceId for device-specific.
 * @property prefKey Logical preference key.
 * @property prefValue Serialized preference value stored as text.
 * @property updatedAt Timestamp when the preference was last updated.
 */
object UserPreferencesTable : LongIdTable("user_preferences") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val deviceId = reference("device_id", UserDevicesTable, onDelete = ReferenceOption.CASCADE).nullable()
    val scopeId = varchar("scope_id", 36)
    val prefKey = varchar("pref_key", 255)
    val prefValue = text("pref_value")
    val updatedAt = long("updated_at")

    init {
        uniqueIndex("user_preferences_user_id_pref_key_scope_id_unique", userId, prefKey, scopeId)
    }
}
