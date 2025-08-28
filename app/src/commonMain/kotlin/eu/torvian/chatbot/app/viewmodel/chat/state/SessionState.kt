package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for session-related state operations.
 * Handles session lifecycle, navigation, streaming, and retry state.
 */
interface SessionState {

    // --- Read-only State Properties ---

    /**
     * The state of the currently loaded chat session combined with its model settings.
     * When in Success state, provides the ChatSessionData object containing both session and settings.
     * This is the primary state property that should be used for accessing session data with settings.
     */
    val sessionDataState: StateFlow<UiState<ApiError, ChatSessionData>>

    /**
     * The list of messages to display in the UI, representing the currently selected thread branch.
     * This is derived from the session's full list of messages and the current leaf message ID.
     */
    val displayedMessages: StateFlow<List<ChatMessage>>

    /**
     * The ID of the session that was last attempted to be loaded.
     * Used for retry functionality.
     */
    val lastAttemptedSessionId: StateFlow<Long?>

    /**
     * The event ID of the last failed load operation.
     * Used for retry functionality.
     */
    val lastFailedLoadEventId: StateFlow<String?>

    /**
     * Convenience property to get the current session data if available.
     * Returns null if the session data state is not in Success state.
     */
    val currentSessionData: ChatSessionData?
        get() = sessionDataState.value.dataOrNull

    /**
     * Convenience property to get the current session if available.
     * Returns null if the session data state is not in Success state.
     */
    val currentSession: ChatSession?
        get() = currentSessionData?.session

    /**
     * Convenience property to get the current leaf message ID if available.
     * Returns null if the session data state is not in Success state.
     */
    val currentBranchLeafId: Long?
        get() = currentSession?.currentLeafMessageId

    // --- State Mutation Methods ---

    /**
     * Sets the session data state to loading.
     */
    fun setSessionDataLoading()

    /**
     * Sets the session data state to error.
     */
    fun setSessionDataError(error: ApiError)

    /**
     * Sets the session data state to success with the provided session and model settings.
     */
    fun setSessionDataSuccess(sessionData: ChatSessionData)

    /**
     * Updates the session messages.
     */
    fun updateSessionMessages(messages: List<ChatMessage>)

    /**
     * Updates the session's current leaf message ID.
     */
    fun updateSessionLeafId(leafId: Long?)

    /**
     * Updates the session's current model ID.
     */
    fun updateSessionModelId(modelId: Long?)

    /**
     * Updates the session's current settings ID.
     */
    fun updateSessionSettingsId(settingsId: Long?)

    /**
     * Sets the retry state for session loading operations.
     */
    fun setRetryState(sessionId: Long?, eventId: String?)

    /**
     * Clears the retry state.
     */
    fun clearRetryState()
}
