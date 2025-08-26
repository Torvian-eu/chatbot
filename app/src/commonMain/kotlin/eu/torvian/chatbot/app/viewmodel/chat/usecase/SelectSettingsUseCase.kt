package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_updating_session_settings
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.SessionState
import eu.torvian.chatbot.common.models.UpdateSessionSettingsRequest

/**
 * Use case for selecting settings for the current chat session.
 * Updates the session's current settings via the API.
 */
class SelectSettingsUseCase(
    private val sessionApi: SessionApi,
    private val state: SessionState,
    private val errorNotifier: ErrorNotifier
) {

    private val logger = kmpLogger<SelectSettingsUseCase>()

    /**
     * Selects settings for the current session.
     *
     * @param settingsId The ID of the settings to select, or null to clear the selection
     */
    suspend fun execute(settingsId: Long?) {
        val currentSession = state.sessionState.value.dataOrNull ?: return

        logger.info("Selecting settings $settingsId for session ${currentSession.id}")

        // Update session settings via API
        sessionApi.updateSessionSettings(
            sessionId = currentSession.id,
            request = UpdateSessionSettingsRequest(settingsId = settingsId)
        ).fold(
            ifLeft = { error ->
                logger.error("Failed to update session settings: $error")
                errorNotifier.apiError(
                    error = error,
                    shortMessageRes = Res.string.error_updating_session_settings,
                    isRetryable = true
                )
            },
            ifRight = {
                logger.info("Successfully updated session settings")
                // Update local state after successful API call
                state.updateSessionSettingsId(settingsId)
            }
        )
    }
}
