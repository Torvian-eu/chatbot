package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_loading_session
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatSessionData

/**
 * Use case for loading chat sessions from the API.
 * Handles session loading and error handling.
 */
class LoadSessionUseCase(
    private val sessionApi: SessionApi,
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

        // Store the session ID for potential retry in SharedChatState
        state.setRetryState(sessionId, null)

        state.setSessionDataLoading()
        state.setReplyTarget(null)
        state.setEditingMessage(null)
        state.setCurrentLeafId(null)

        sessionApi.getSessionDetails(sessionId)
            .fold(
                ifLeft = { error ->
                    // Handle Error case
                    state.setSessionDataError(error)
                    // Emit to generic EventBus using the specific error type
                    val eventId = errorNotifier.apiError(
                        error = error,
                        shortMessageRes = Res.string.error_loading_session,
                        isRetryable = true
                    )
                    // Store event ID in SharedChatState for retry functionality
                    state.setRetryState(sessionId, eventId)
                },
                ifRight = { session ->
                    // Handle Success case
                    logger.info("Successfully loaded session: ${session.id}")
                    state.setSessionDataSuccess(ChatSessionData(session = session, modelSettings = null))
                    state.setCurrentLeafId(session.currentLeafMessageId)
                    state.clearRetryState()
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
