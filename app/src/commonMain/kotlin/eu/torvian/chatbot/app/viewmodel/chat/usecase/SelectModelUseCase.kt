package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_updating_session_model
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.SessionState
import eu.torvian.chatbot.common.models.UpdateSessionModelRequest

/**
 * Use case for selecting a model for the current chat session.
 * Updates the session's current model via the API.
 */
class SelectModelUseCase(
    private val sessionApi: SessionApi,
    private val state: SessionState,
    private val errorNotifier: ErrorNotifier
) {

    private val logger = kmpLogger<SelectModelUseCase>()

    /**
     * Selects a model for the current session.
     *
     * @param modelId The ID of the model to select, or null to clear the selection
     */
    suspend fun execute(modelId: Long?) {
        val currentSession = state.currentSession ?: return

        logger.info("Selecting model $modelId for session ${currentSession.id}")

        // Update session model via API
        sessionApi.updateSessionModel(
            sessionId = currentSession.id,
            request = UpdateSessionModelRequest(modelId = modelId)
        ).fold(
            ifLeft = { error ->
                logger.error("Failed to update session model: $error")
                errorNotifier.apiError(
                    error = error,
                    shortMessageRes = Res.string.error_updating_session_model,
                    isRetryable = true
                )
            },
            ifRight = {
                logger.info("Successfully updated session model")
                // Update local state after successful API call
                state.updateSessionModelId(modelId)
            }
        )
    }
}
