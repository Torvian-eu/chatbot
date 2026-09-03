package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.UserPreferenceRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.UserPreferenceApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.api.me.PreferenceDetailDTO
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.common.models.api.me.UserPreferenceDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Default implementation of [UserPreferenceRepository].
 *
 * Keeps the current theme preference and the server built-in tool name prefix in in-memory
 * [StateFlow]s so the UI can observe changes reactively. Server failures are handled gracefully:
 * on sync failure the theme flow falls back to `null` (system theme).
 *
 * @property api The low-level API client for /api/v1/me preferences.
 */
class DefaultUserPreferenceRepository(
    private val api: UserPreferenceApi
) : UserPreferenceRepository {

    companion object {
        private val logger = kmpLogger<DefaultUserPreferenceRepository>()
    }

    private val _theme = MutableStateFlow<String?>(null)
    override val theme: StateFlow<String?> = _theme.asStateFlow()

    private val _serverBuiltInToolNamePrefix = MutableStateFlow<String?>(null)
    override val serverBuiltInToolNamePrefix: StateFlow<String?> = _serverBuiltInToolNamePrefix.asStateFlow()

    private val _detailedPreferences = MutableStateFlow<Map<String, PreferenceDetailDTO>>(emptyMap())
    override val detailedPreferences: StateFlow<Map<String, PreferenceDetailDTO>> = _detailedPreferences.asStateFlow()

    private val _compactionPreference = MutableStateFlow<ConversationCompactionPreference?>(null)
    override val compactionPreference: StateFlow<ConversationCompactionPreference?> = _compactionPreference.asStateFlow()

    /** Lenient JSON codec matching the server's canonical preference encoding. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    override suspend fun syncPreferences(): Either<RepositoryError, Unit> = either {
        val preferences = withError({ apiError ->
            apiError.toRepositoryError("Failed to sync preferences")
        }) {
            api.getPreferences().bind()
        }

        _theme.value = preferences["current_theme"]
        // The prefix key is only present once the user has stored a value; absent means the server
        // default applies, which the settings UI surfaces as a placeholder hint.
        _serverBuiltInToolNamePrefix.value = preferences[PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX]
        // The compaction key is only present once a global row exists; absent means automatic
        // compaction is disabled. A stored value that cannot be decoded (e.g. edited by hand on the
        // server) is treated as absent so the settings UI shows the disabled state instead of
        // crashing; the runtime turn still surfaces the server's own invalid-configuration error.
        _compactionPreference.value = preferences[PreferenceKeys.CONVERSATION_COMPACTION]
            ?.let { rawValue ->
                runCatching {
                    json.decodeFromString<ConversationCompactionPreference>(rawValue)
                }.getOrElse { decodeError ->
                    logger.warn(
                        "Failed to decode conversation_compaction preference (treating as disabled): " +
                            "${decodeError.message}"
                    )
                    null
                }
            }

        logger.info("Synced theme preference: ${_theme.value}")
    }.onLeft { error ->
        logger.warn("Failed to sync preferences, falling back to system theme: ${error.message}")
        _theme.value = null
    }

    override suspend fun syncDetailedPreferences(): Either<RepositoryError, Unit> = either {
        val details = withError({ apiError ->
            apiError.toRepositoryError("Failed to sync detailed preferences")
        }) {
            api.getDetailedPreferences().bind()
        }

        _detailedPreferences.value = details
        logger.info("Synced ${details.size} detailed preferences")
    }.onLeft { error ->
        logger.warn("Failed to sync detailed preferences: ${error.message}")
    }

    override suspend fun setTheme(
        theme: String?,
        scope: PreferenceScope
    ): Either<RepositoryError, Unit> = either {
        if (theme != null) {
            val dto = UserPreferenceDTO(
                key = "current_theme",
                value = theme,
                scope = scope
            )
            withError({ apiError ->
                apiError.toRepositoryError("Failed to update theme preference")
            }) {
                api.updatePreference("current_theme", dto).bind()
            }
        } else {
            withError({ apiError ->
                apiError.toRepositoryError("Failed to delete theme preference")
            }) {
                api.deletePreference("current_theme", scope).bind()
            }
        }
        syncPreferences().bind()
        syncDetailedPreferences().bind()
        logger.info("Updated theme preference to: $theme")
    }

    override suspend fun setServerBuiltInToolNamePrefix(prefix: String): Either<RepositoryError, Unit> = either {
        val dto = UserPreferenceDTO(
            key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
            value = prefix,
            // The prefix governs server-side execution, so it is always stored globally.
            scope = PreferenceScope.GLOBAL
        )
        withError({ apiError ->
            apiError.toRepositoryError("Failed to update tool name prefix")
        }) {
            api.updatePreference(PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX, dto).bind()
        }
        syncPreferences().bind()
        syncDetailedPreferences().bind()
        logger.info("Updated server built-in tool name prefix to: $prefix")
    }

    override suspend fun resetServerBuiltInToolNamePrefix(): Either<RepositoryError, Unit> = either {
        withError({ apiError ->
            apiError.toRepositoryError("Failed to reset tool name prefix")
        }) {
            api.deletePreference(PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX, PreferenceScope.GLOBAL).bind()
        }
        syncPreferences().bind()
        syncDetailedPreferences().bind()
        logger.info("Reset server built-in tool name prefix")
    }

    override suspend fun setCompactionPreference(
        preference: ConversationCompactionPreference
    ): Either<RepositoryError, Unit> = either {
        val dto = UserPreferenceDTO(
            key = PreferenceKeys.CONVERSATION_COMPACTION,
            // Canonical JSON keeps the client and the server on the same decoding contract; the
            // server re-validates and stores its own canonical encoding of the same value.
            value = json.encodeToString(ConversationCompactionPreference.serializer(), preference),
            scope = PreferenceScope.GLOBAL
        )
        withError({ apiError ->
            apiError.toRepositoryError("Failed to update conversation compaction preference")
        }) {
            api.updatePreference(PreferenceKeys.CONVERSATION_COMPACTION, dto).bind()
        }
        syncPreferences().bind()
        syncDetailedPreferences().bind()
        logger.info("Updated conversation compaction preference")
    }

    override suspend fun clearCompactionPreference(): Either<RepositoryError, Unit> = either {
        withError({ apiError ->
            apiError.toRepositoryError("Failed to clear conversation compaction preference")
        }) {
            api.deletePreference(PreferenceKeys.CONVERSATION_COMPACTION, PreferenceScope.GLOBAL).bind()
        }
        syncPreferences().bind()
        syncDetailedPreferences().bind()
        logger.info("Cleared conversation compaction preference")
    }
}
