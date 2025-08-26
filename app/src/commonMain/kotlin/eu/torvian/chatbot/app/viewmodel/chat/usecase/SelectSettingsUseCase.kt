package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_updating_session_settings
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.SessionState
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.models.ChatModelSettings
import eu.torvian.chatbot.common.models.UpdateSessionSettingsRequest

/**
 * Use case for selecting settings for the current chat session.
 * Updates the session's current settings via the API.
 */
class SelectSettingsUseCase(
    private val sessionApi: SessionApi,
    private val settingsApi: SettingsApi,
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
        val currentSession = state.currentSession ?: return

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
                    shortMessageRes = Res.string.error_updating_session_settings
                )
            },
            ifRight = {
                logger.info("Successfully updated session settings")
                // Update local state after successful API call
                state.updateSessionSettingsId(settingsId)

                // Load model settings into ChatSessionData
                loadSettingsIntoSessionData(settingsId)
            }
        )
    }

    /**
     * Loads the model settings data into the current ChatSessionData.
     * This ensures the session data reflects the newly selected settings.
     */
    private suspend fun loadSettingsIntoSessionData(settingsId: Long?) {
        val currentSessionData = state.currentSessionData ?: return

        if (settingsId == null) {
            // Clear settings when settingsId is null
            val updatedSessionData = currentSessionData.copy(
                modelSettings = null
            )
            state.setSessionDataSuccess(updatedSessionData)
            logger.info("Cleared settings from session data")
            return
        }

        // Load the settings data from API
        settingsApi.getSettingsById(settingsId).fold(
            ifLeft = { error ->
                logger.error("Failed to load settings data for settingsId $settingsId: $error")
                state.setSessionDataError(error)
            },
            ifRight = { settings ->
                logger.info("Successfully loaded settings data for ${settings.name}")
                // Check if settings is ChatModelSettings
                if (settings !is ChatModelSettings) {
                    logger.error("Settings $settingsId is not a ChatModelSettings instance, ignoring")
                    state.setSessionDataError(
                        apiError(
                            CommonApiErrorCodes.INTERNAL,
                            "Settings $settingsId is not a ChatModelSettings instance, ignoring"
                        )
                    )
                    return
                }

                // Update session data with loaded settings
                val updatedSessionData = currentSessionData.copy(
                    modelSettings = settings
                )
                state.setSessionDataSuccess(updatedSessionData)
            }
        )
    }
}
