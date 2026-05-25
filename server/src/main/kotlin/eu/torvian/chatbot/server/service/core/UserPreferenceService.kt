package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.me.PreferenceDetailDTO
import eu.torvian.chatbot.common.models.api.me.UserPreferenceDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope
import eu.torvian.chatbot.server.service.core.error.preferences.PreferenceError

/**
 * Service for reading and writing user preferences with global and device-specific scopes.
 */
interface UserPreferenceService {
    /**
     * Resolves the effective preferences for a user, optionally overlaying the current device's values.
     *
     * @param userId Authenticated user identifier derived from the JWT principal.
     * @param clientDeviceId Optional client-side UUID used to resolve a device-specific preference layer.
     * @return Either a logical preference error or the merged key/value preference map.
     */
    suspend fun getResolvedPreferences(
        userId: Long,
        clientDeviceId: String?
    ): Either<PreferenceError, Map<String, String>>

    /**
     * Retrieves detailed preference information showing both global and device-specific values.
     *
     * This method is used by the Settings UI to display the inheritance chain, allowing users
     * to see which value is effective and whether a device override exists.
     *
     * @param userId Authenticated user identifier derived from the JWT principal.
     * @param clientDeviceId Optional client-side UUID for the current device.
     * @return Either a logical preference error or a map of preference keys to their detailed values.
     */
    suspend fun getDetailedPreferences(
        userId: Long,
        clientDeviceId: String?
    ): Either<PreferenceError, Map<String, PreferenceDetailDTO>>

    /**
     * Stores a preference in either the global layer or the current device layer.
     *
     * @param userId Authenticated user identifier derived from the JWT principal.
     * @param clientDeviceId Optional client-side UUID for device-scoped preferences.
     * @param pathKey Preference key from the request path.
     * @param request Serialized preference payload from the request body.
     * @return Either a logical preference error or Unit on success.
     */
    suspend fun updatePreference(
        userId: Long,
        clientDeviceId: String?,
        pathKey: String,
        request: UserPreferenceDTO
    ): Either<PreferenceError, Unit>

    /**
     * Deletes a preference in either the global layer or the current device layer.
     *
     * @param userId Authenticated user identifier derived from the JWT principal.
     * @param clientDeviceId Optional client-side UUID for device-scoped preferences.
     * @param pathKey Preference key from the request path.
     * @param scope The scope of the preference to delete. GLOBAL deletes the global preference,
     *               DEVICE deletes the device-specific preference.
     * @return Either a logical preference error or Unit on success.
     */
    suspend fun deletePreference(
        userId: Long,
        clientDeviceId: String?,
        pathKey: String,
        scope: PreferenceScope
    ): Either<PreferenceError, Unit>
}
