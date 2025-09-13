package eu.torvian.chatbot.app.viewmodel.chat.usecase

import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_loading_session
import eu.torvian.chatbot.app.repository.ModelRepository
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.SettingsRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier

/**
 * Use case for loading chat sessions in the reactive architecture.
 *
 * This use case follows the action-only pattern where it:
 * 1. Sets the activeSessionId to trigger reactive state updates
 * 2. Triggers repository load operations
 * 3. Lets the reactive system handle all state updates automatically
 */
class LoadSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val state: ChatState,
    private val errorNotifier: ErrorNotifier
) {

    private val logger = kmpLogger<LoadSessionUseCase>()

    /**
     * Loads a chat session and its messages by ID in the reactive architecture.
     *
     * This method sets the activeSessionId and triggers repository load operations.
     * The reactive system automatically handles state updates through ChatStateImpl's
     * reactive flows.
     *
     * @param sessionId The ID of the session to load
     * @param forceReload If true, reloads the session even if it's already loaded successfully
     */
    suspend fun execute(sessionId: Long, forceReload: Boolean = false) {
        // Prevent reloading if already loading or if the session is already loaded successfully
        val currentState = state.sessionDataState.value
        if (!forceReload && (currentState.isLoading || (currentState.dataOrNull?.id == sessionId))) return

        // Store the session ID for potential retry
        state.setRetryState(sessionId, null)

        parZip(
            { sessionRepository.loadSessionDetails(sessionId) },
            { modelRepository.loadModels() },
            { settingsRepository.loadSettings() }

        ) { sessionResult, _, _ ->
            sessionResult
        }.fold(
            ifLeft = { repositoryError ->
                val eventId = errorNotifier.repositoryError(
                    error = repositoryError,
                    shortMessageRes = Res.string.error_loading_session,
                    isRetryable = true
                )
                state.setRetryState(sessionId, eventId)
            },
            ifRight = { session ->
                logger.info("Successfully loaded session $sessionId. Dependencies are loading in background.")
                state.resetState()
                state.setActiveSessionId(sessionId)
            }
        )
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
