package eu.torvian.chatbot.server.data.dao

import eu.torvian.chatbot.server.data.entities.UserPreferenceEntity

/**
 * Data access operations for user preferences.
 *
 * Preferences are stored as text so callers can persist JSON payloads or primitives.
 */
interface UserPreferenceDao {
    /**
     * Retrieves the global preference rows and, when a device is provided, the matching device-scoped rows.
     *
     * @param userId Owning user identifier.
     * @param internalDeviceId Optional internal device identifier for the current client device.
     * @return Matching preference rows for the user.
     */
    suspend fun getPreferencesForUser(userId: Long, internalDeviceId: Long?): List<UserPreferenceEntity>

    /**
     * Inserts or updates a preference row for the supplied scope.
     *
     * @param userId Owning user identifier.
     * @param internalDeviceId Optional internal device identifier for the FK relationship.
     * @param clientDeviceId Client-side device UUID for device-scoped preferences; null for global scope.
     * @param key Logical preference key.
     * @param value Serialized preference value.
     */
    suspend fun upsertPreference(userId: Long, internalDeviceId: Long?, clientDeviceId: String?, key: String, value: String)

    /**
     * Deletes a preference row for the supplied scope.
     *
     * @param userId Owning user identifier.
     * @param internalDeviceId Optional internal device identifier; null selects the global scope.
     * @param key Logical preference key.
     */
    suspend fun deletePreference(userId: Long, internalDeviceId: Long?, key: String)
}
