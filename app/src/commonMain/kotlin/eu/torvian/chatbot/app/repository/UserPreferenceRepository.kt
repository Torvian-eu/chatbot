package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.api.me.PreferenceDetailDTO
import eu.torvian.chatbot.common.models.api.me.TurnNotificationPreference
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
     * Reactive stream of the user's stored turn-status notification toggles, or `null` when no
     * global row exists.
     *
     * A `null` value means the user never configured the feature; consumers apply
     * [TurnNotificationPreference.DEFAULT] rather than reading the absence as disabled.
     */
    val turnNotificationPreference: StateFlow<TurnNotificationPreference?>

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
     * refreshed from the server afterwards. The stored
     * [ConversationCompactionPreference.automaticCompactionEnabled] flag governs **automatic**
     * (threshold-triggered) compaction only: the server compacts at the threshold only while that flag
     * AND the session preset's own flag are enabled, and storing the flag as `false` keeps the rest of
     * the configuration for a later re-enable. Compaction requested from the chat top bar stays
     * available either way, as long as the stored configuration is complete.
     *
     * @param preference The validated configuration to store (model/settings ids, instruction,
     *            threshold, automatic-compaction flag).
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with a [RepositoryError] on failure.
     */
    suspend fun setCompactionPreference(
        preference: ConversationCompactionPreference
    ): Either<RepositoryError, Unit>

    /**
     * Deletes the global conversation-compaction configuration.
     *
     * Sends `DELETE /api/v1/me/preferences/conversation_compaction`; after deletion the [compactionPreference]
     * state becomes `null`. The stored configuration is not preserved — the user re-enters it when
     * re-enabling. The server contract treats an absent row as an unusable configuration, so a
     * compaction requested from the chat top bar fails with a typed error until a new configuration is
     * stored.
     *
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with a [RepositoryError] on failure.
     */
    suspend fun clearCompactionPreference(): Either<RepositoryError, Unit>

    /**
     * Stores the user's turn-status notification toggles as one GLOBAL preference row.
     *
     * The whole [TurnNotificationPreference] is written at once so the toggles can never be
     * persisted in a partially updated state, and the local [turnNotificationPreference] state is
     * refreshed from the server afterwards so the UI shows the authoritative value.
     *
     * @param preference The toggles to store.
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with a [RepositoryError] on failure.
     */
    suspend fun setTurnNotificationPreference(
        preference: TurnNotificationPreference
    ): Either<RepositoryError, Unit>
}
