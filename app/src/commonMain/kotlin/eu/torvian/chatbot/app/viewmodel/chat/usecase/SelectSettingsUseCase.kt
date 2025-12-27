package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_updating_session_settings
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService

/**
 * Use case for selecting settings for the current chat session in the reactive architecture.
 *
 * This use case follows the action-only pattern where it:
 * 1. Updates the session's settings via repository
 * 2. Lets the reactive system handle all state updates automatically
 */
class SelectSettingsUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val notificationService: NotificationService
) {

    private val logger = kmpLogger<SelectSettingsUseCase>()

    /**
     * Selects settings for the current session in the reactive architecture.
     *
     * This method updates the session's settings via repository and lets the reactive
     * system handle all state updates automatically.
     *
     * @param settingsId The ID of the settings to select, or null to clear the selection
     */
    suspend fun execute(settingsId: Long?) {
        val sessionId = state.activeSessionId.value ?: return
        logger.info("Selecting settings $settingsId for session $sessionId")

        // Update session settings via repository
        sessionRepository.updateSessionSettings(
            sessionId = sessionId,
            settingsId = settingsId
        ).fold(
            ifLeft = { repositoryError ->
                logger.error("Failed to update session settings: $repositoryError")
                notificationService.repositoryError(
                    error = repositoryError,
                    shortMessageRes = Res.string.error_updating_session_settings
                )
            },
            ifRight = {
                logger.info("Successfully updated session settings")
            }
        )
    }


}
