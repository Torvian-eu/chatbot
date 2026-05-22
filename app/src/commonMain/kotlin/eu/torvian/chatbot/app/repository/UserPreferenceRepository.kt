package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.me.PreferenceDetailDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for managing the current user's preferences, exposing reactive state for UI consumption.
 *
 * All methods are suspend functions and return [Either<RepositoryError, T>].
 */
interface UserPreferenceRepository {

    /**
     * Reactive stream of the user's theme preference as a string.
     *
     * - `"dark"`  -> force dark theme
     * - `"light"` -> force light theme
     * - `null`    -> follow the system setting (no preference set)
     */
    val theme: StateFlow<String?>

    /**
     * Reactive stream of detailed preferences showing both global and device-specific values.
     *
     * This map is used by the Settings UI to display the inheritance chain,
     * allowing users to see which value is effective and whether a device override exists.
     */
    val detailedPreferences: StateFlow<Map<String, PreferenceDetailDTO>>

    /**
     * Fetches the current user's resolved preferences from the server
     * and updates [theme] based on the `"current_theme"` key.
     *
     * On failure, [theme] is reset to `null` so the app falls back
     * to the system theme.
     *
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with a [RepositoryError] on failure.
     */
    suspend fun syncPreferences(): Either<RepositoryError, Unit>

    /**
     * Fetches detailed preferences from the server showing both global and device-specific values.
     *
     * This method updates [detailedPreferences] and is used by the Settings UI
     * to display the inheritance chain.
     *
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with a [RepositoryError] on failure.
     */
    suspend fun syncDetailedPreferences(): Either<RepositoryError, Unit>

    /**
     * Updates the user's theme preference locally and on the server.
     *
     * - A non-null [theme] is sent via `PUT /api/v1/me/preferences/current_theme`.
     * - `null` removes the preference via `DELETE /api/v1/me/preferences/current_theme`.
     *
     * The local [theme] state is updated immediately so the UI reacts without delay.
     *
     * @param theme The desired theme string value (e.g., "dark", "light"), or `null` to clear it.
     * @param scope Whether the preference should be stored globally or device-scoped.
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with a [RepositoryError] on failure.
     */
    suspend fun setTheme(
        theme: String?,
        scope: PreferenceScope
    ): Either<RepositoryError, Unit>
}
