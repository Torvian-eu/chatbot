package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.UserPreferenceRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.UserPreferenceApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.me.PreferenceDetailDTO
import eu.torvian.chatbot.common.models.api.me.UserPreferenceDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default implementation of [UserPreferenceRepository].
 *
 * Keeps the current theme preference in an in-memory [StateFlow] so the UI
 * can observe changes reactively. Server failures are handled gracefully:
 * on sync failure the flow falls back to `null` (system theme).
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

    private val _detailedPreferences = MutableStateFlow<Map<String, PreferenceDetailDTO>>(emptyMap())
    override val detailedPreferences: StateFlow<Map<String, PreferenceDetailDTO>> = _detailedPreferences.asStateFlow()

    override suspend fun syncPreferences(): Either<RepositoryError, Unit> = either {
        val preferences = withError({ apiError ->
            apiError.toRepositoryError("Failed to sync preferences")
        }) {
            api.getPreferences().bind()
        }

        _theme.value = preferences["current_theme"]

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
}
