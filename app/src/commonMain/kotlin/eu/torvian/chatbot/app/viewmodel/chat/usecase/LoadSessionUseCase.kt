package eu.torvian.chatbot.app.viewmodel.chat.usecase

import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_loading_session
import eu.torvian.chatbot.app.service.api.ModelApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatSessionData
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.models.ChatModelSettings
import eu.torvian.chatbot.common.models.LLMModel

/**
 * Use case for loading chat sessions from the API.
 * Handles session loading and error handling.
 */
class LoadSessionUseCase(
    private val sessionApi: SessionApi,
    private val settingsApi: SettingsApi,
    private val modelApi: ModelApi,
    private val state: ChatState,
    private val errorNotifier: ErrorNotifier
) {

    private val logger = kmpLogger<LoadSessionUseCase>()

    /**
     * Loads a chat session and its messages by ID.
     *
     * @param sessionId The ID of the session to load, or null to clear the session
     * @param forceReload If true, reloads the session even if it's already loaded successfully
     */
    suspend fun execute(sessionId: Long, forceReload: Boolean = false) {
        // Prevent reloading if already loading or if the session is already loaded successfully
        val currentState = state.sessionDataState.value
        if (!forceReload && (currentState.isLoading || (currentState.dataOrNull?.session?.id == sessionId))) return

        // Store the session ID for potential retry
        state.setRetryState(sessionId, null)

        state.setSessionDataLoading()
        state.setReplyTarget(null)
        state.setEditingMessage(null)

        // Load session and related data
        either {
            val session = sessionApi.getSessionDetails(sessionId).bind()

            // Load LLM model and model settings in parallel
            val (llmModel, modelSettings) = parZip(
                { session.currentModelId?.let { modelId -> loadLLMModel(modelId) } },
                { session.currentSettingsId?.let { settingsId -> loadModelSettings(settingsId) } }
            ) { llmModel, modelSettings ->
                llmModel to modelSettings
            }
            ChatSessionData(session = session, modelSettings = modelSettings, llmModel = llmModel)
        }.fold(
            ifLeft = { error ->
                state.setSessionDataError(error)
                val eventId = errorNotifier.apiError(
                    error = error,
                    shortMessageRes = Res.string.error_loading_session,
                    isRetryable = true
                )
                state.setRetryState(sessionId, eventId)
            },
            ifRight = { data ->
                logger.info("Successfully loaded session: ${data.session.id}")
                if (data.llmModel == null) {
                    logger.debug("Session ${data.session.id} has no LLM model configured")
                } else {
                    logger.info("Successfully loaded LLM model: ${data.llmModel.name} (ID: ${data.llmModel.id})")
                }
                if (data.modelSettings == null) {
                    logger.debug("Session ${data.session.id} has no model settings configured")
                } else {
                    logger.info("Successfully loaded model settings: ${data.modelSettings.name} (ID: ${data.modelSettings.id})")
                }
                state.setSessionDataSuccess(data)
                state.clearRetryState()
            }
        )
    }

    /**
     * Loads model settings by ID.
     *
     * @param settingsId The ID of the model settings to load
     * @return Either an ApiError or the loaded ChatModelSettings
     */
    private suspend fun Raise<ApiError>.loadModelSettings(settingsId: Long): ChatModelSettings {
        val modelSettings = settingsApi.getSettingsById(settingsId).bind()
        ensure(modelSettings is ChatModelSettings) {
            val errorMessage =
                "Unexpected model settings type: ${modelSettings::class.simpleName}. Expected ChatModelSettings."
            logger.warn(errorMessage)
            apiError(CommonApiErrorCodes.INTERNAL, errorMessage)
        }
        return modelSettings
    }

    /**
     * Loads the LLM model by ID.
     *
     * @param modelId The ID of the LLM model to load
     * @return Either an ApiError or the loaded LLMModel
     */
    private suspend fun Raise<ApiError>.loadLLMModel(modelId: Long): LLMModel {
        return modelApi.getModelById(modelId).bind()
    }

    /**
     * Handles retry requests by checking if the event ID matches and retrying if so.
     *
     * @param eventId The event ID from the retry interaction
     * @return true if retry was performed, false otherwise
     */
    suspend fun handleRetry(eventId: String): Boolean {
        val sessionId = state.lastAttemptedSessionId.value
        return if (state.lastFailedLoadEventId.value == eventId && sessionId != null) {
            logger.info("Retrying loadSession due to Snackbar action!")
            state.clearRetryState()
            execute(sessionId, forceReload = true)
            true
        } else {
            false
        }
    }
}
