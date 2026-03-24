package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_updating_session_model
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService

/**
 * Use case for selecting a model for the current chat session in the reactive architecture.
 *
 * This use case follows the action-only pattern where it:
 * 1. Updates the session's model via repository
 * 2. Optionally asks backend to auto-select the first available settings profile for the model
 * 3. Lets the reactive system handle all state updates automatically
 */
class SelectModelUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val notificationService: NotificationService
) {

    private val logger = kmpLogger<SelectModelUseCase>()

    /**
     * Selects a model for the current session in the reactive architecture.
     *
     * This method:
     * 1. Updates the session's model via repository
     * 2. Delegates optional first-settings auto-selection to backend
     *
     * @param modelId The ID of the model to select, or null to clear the selection
     */
    suspend fun execute(modelId: Long?) {
        val sessionId = state.activeSessionId.value ?: return
        logger.info("Selecting model $modelId for session $sessionId")

        // Update session model via repository
        sessionRepository.updateSessionModel(
            sessionId = sessionId,
            modelId = modelId,
            autoSelectFirstAvailableSettings = modelId != null
        ).fold(
            ifLeft = { repositoryError ->
                logger.error("Failed to update session model: $repositoryError")
                notificationService.repositoryError(
                    error = repositoryError,
                    shortMessageRes = Res.string.error_updating_session_model
                )
            },
            ifRight = {
                logger.info("Successfully updated session model")
            }
        )
    }

}
