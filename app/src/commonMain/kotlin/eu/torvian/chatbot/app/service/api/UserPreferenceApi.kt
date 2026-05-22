package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.me.PreferenceDetailDTO
import eu.torvian.chatbot.common.models.api.me.UserPreferenceDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope

/**
 * Frontend API interface for authenticated self-service preference endpoints (/api/v1/me).
 *
 * All methods are suspend functions and return [Either<ApiResourceError, T>].
 */
interface UserPreferenceApi {

    /**
     * Retrieves the resolved effective preferences for the authenticated user.
     *
     * Corresponds to `GET /api/v1/me/preferences`.
     *
     * @return [Either.Right] containing a map of preference keys to values on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getPreferences(): Either<ApiResourceError, Map<String, String>>

    /**
     * Retrieves detailed preferences showing both global and device-specific values.
     *
     * Corresponds to `GET /api/v1/me/preferences/details`.
     *
     * This endpoint is used by the Settings UI to display the inheritance chain,
     * allowing users to see which value is effective and whether a device override exists.
     *
     * @return [Either.Right] containing a map of preference keys to [PreferenceDetailDTO] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getDetailedPreferences(): Either<ApiResourceError, Map<String, PreferenceDetailDTO>>

    /**
     * Stores or updates a single preference value.
     *
     * Corresponds to `PUT /api/v1/me/preferences/{key}`.
     *
     * @param key The preference key to update.
     * @param dto The preference payload containing value and scope.
     * @return [Either.Right] with [Unit] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updatePreference(key: String, dto: UserPreferenceDTO): Either<ApiResourceError, Unit>

    /**
     * Deletes a single preference value.
     *
     * Corresponds to `DELETE /api/v1/me/preferences/{key}`.
     *
     * @param key The preference key to delete.
     * @param scope The scope of the preference to delete. GLOBAL deletes the global preference,
     *               DEVICE deletes the device-specific preference.
     * @return [Either.Right] with [Unit] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun deletePreference(key: String, scope: PreferenceScope): Either<ApiResourceError, Unit>
}
