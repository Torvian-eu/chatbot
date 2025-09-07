package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_updating_session_model
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.common.models.UpdateSessionModelRequest

/**
 * Use case for selecting a model for the current chat session in the reactive architecture.
 *
 * This use case follows the action-only pattern where it:
 * 1. Updates the session's model via repository
 * 2. Lets the reactive system handle all state updates automatically
 */
class SelectModelUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val errorNotifier: ErrorNotifier
) {

    private val logger = kmpLogger<SelectModelUseCase>()

    /**
     * Selects a model for the current session in the reactive architecture.
     *
     * This method updates the session's model via repository and lets the reactive
     * system handle all state updates automatically.
     *
     * @param modelId The ID of the model to select, or null to clear the selection
     */
    suspend fun execute(modelId: Long?) {
        val sessionId = state.activeSessionId.value ?: return

        logger.info("Selecting model $modelId for session $sessionId")

        // Update session model via repository
        sessionRepository.updateSessionModel(
            sessionId = sessionId,
            request = UpdateSessionModelRequest(modelId = modelId)
        ).fold(
            ifLeft = { repositoryError ->
                logger.error("Failed to update session model: $repositoryError")
                errorNotifier.repositoryError(
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
