package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
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
     * Reactive stream of the user's server built-in tool name prefix as a string.
     *
     * - a non-blank value -> the custom prefix (e.g. `"acme-"`);
     * - `""`             -> no prefix (canonical tool names);
     * - `null`            -> no stored preference; the server default (`"chatbot-"`) applies.
     */
    val serverBuiltInToolNamePrefix: StateFlow<String?>

    /**
     * Reactive stream of detailed preferences showing both global and device-specific values.
     *
     * This map is used by the Settings UI to display the inheritance chain,
     * allowing users to see which value is effective and whether a device override exists.
     */
    val detailedPreferences: StateFlow<Map<String, PreferenceDetailDTO>>

    /**
     * Reactive stream of the user's stored conversation-compaction preference, or `null` when no
     * global row exists (automatic compaction is disabled).
     */
    val compactionPreference: StateFlow<ConversationCompactionPreference?>

    /**
     * Fetches the current user's resolved preferences from the server
     * and updates [theme] and [serverBuiltInToolNamePrefix] from their well-known keys.
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

    /**
     * Stores the user's server built-in tool name prefix on the server (GLOBAL scope).
     *
     * A blank [prefix] means "no prefix" (canonical tool names). The local
     * [serverBuiltInToolNamePrefix] state is refreshed from the server afterwards so the UI shows
     * the authoritative value.
     *
     * @param prefix The requested prefix (blank is valid and means no prefix).
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with a [RepositoryError] on failure.
     */
    suspend fun setServerBuiltInToolNamePrefix(prefix: String): Either<RepositoryError, Unit>

    /**
     * Resets the user's server built-in tool name prefix to the server default (`"chatbot-"`).
     *
     * Deletes the global preference row via `DELETE /api/v1/me/preferences/{key}`; the server
     * renames the user's tools back to the default prefix atomically. The local
     * [serverBuiltInToolNamePrefix] state is refreshed from the server afterwards.
     *
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with a [RepositoryError] on failure.
     */
    suspend fun resetServerBuiltInToolNamePrefix(): Either<RepositoryError, Unit>

    /**
     * Stores the user's global conversation-compaction configuration.
     *
     * The preference is encoded to its canonical JSON form and sent via
     * `PUT /api/v1/me/preferences/conversation_compaction` with [PreferenceScope.GLOBAL]. The server
     * re-validates and stores its own canonical encoding; the local [compactionPreference] state is
     * refreshed from the server afterwards. The [ConversationCompactionPreference.enabled] flag is
     * persisted with the row: writing `enabled = false` disables automatic compaction at runtime while
     * preserving the configuration for a later re-enable (a temporary disable that does not delete).
     *
     * @param preference The validated configuration to store (model/settings ids, instruction,
     *            threshold, enabled flag).
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with a [RepositoryError] on failure.
     */
    suspend fun setCompactionPreference(
        preference: ConversationCompactionPreference
    ): Either<RepositoryError, Unit>

    /**
     * Disables automatic conversation compaction by deleting the global preference row.
     *
     * Sends `DELETE /api/v1/me/preferences/conversation_compaction`; after deletion the [compactionPreference]
     * state becomes `null`. The stored configuration is not preserved — the
     * user re-enters it when re-enabling, matching the server contract where an absent row means
     * compaction is disabled.
     *
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with a [RepositoryError] on failure.
     */
    suspend fun clearCompactionPreference(): Either<RepositoryError, Unit>
}
