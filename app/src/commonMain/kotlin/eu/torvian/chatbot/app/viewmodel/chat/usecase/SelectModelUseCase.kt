package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_updating_session_model
import eu.torvian.chatbot.app.service.api.ModelApi
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
    private val modelApi: ModelApi,
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
                    shortMessageRes = Res.string.error_updating_session_model
                )
            },
            ifRight = {
                logger.info("Successfully updated session model")
                // Update local state after successful API call
                state.updateSessionModelId(modelId)

                // Load model into ChatSessionData and clear modelSettings
                loadModelIntoSessionData(modelId)
            }
        )
    }

    /**
     * Loads the model data into the current ChatSessionData and clears modelSettings.
     * This ensures the session data reflects the newly selected model.
     */
    private suspend fun loadModelIntoSessionData(modelId: Long?) {
        val currentSessionData = state.currentSessionData ?: return

        if (modelId == null) {
            // Clear both model and settings when model is null
            val updatedSessionData = currentSessionData.copy(
                llmModel = null,
                modelSettings = null
            )
            state.setSessionDataSuccess(updatedSessionData)
            logger.info("Cleared model and settings from session data")
            return
        }

        // Load the model data from API
        modelApi.getModelById(modelId).fold(
            ifLeft = { error ->
                logger.error("Failed to load model data for modelId $modelId: $error")
                state.setSessionDataError(error)
            },
            ifRight = { model ->
                logger.info("Successfully loaded model data for ${model.name}")
                // Update session data with loaded model and clear settings (since model changed)
                val updatedSessionData = currentSessionData.copy(
                    llmModel = model,
                    modelSettings = null
                )
                state.setSessionDataSuccess(updatedSessionData)
            }
        )
    }
}
