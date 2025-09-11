package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.domain.contracts.ChatAreaDialogState
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.ChatMessage
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for reactive chat state operations.
 *
 * This interface provides a fully reactive state management system where all state
 * is derived from repository data streams. The state is observed, not duplicated,
 * and changes automatically when underlying repository data changes.
 */
interface ChatState {

    // --- Read-only State Properties ---

    /**
     * The ID of the currently active session.
     * This is the primary driver for all reactive state derivation.
     */
    val activeSessionId: StateFlow<Long?>

    /**
     * The state of the currently loaded chat session combined with its model settings.
     * This is reactively derived from the activeSessionId and repository flows.
     * When in Success state, provides the ChatSessionData object containing both session and settings.
     */
    val sessionDataState: StateFlow<DataState<RepositoryError, ChatSessionData>>

    /**
     * The list of messages to display in the UI, representing the currently selected thread branch.
     * This is derived from the session's full list of messages and the current leaf message ID.
     */
    val displayedMessages: StateFlow<List<ChatMessage>>

    /**
     * The current text content in the message input field.
     */
    val inputContent: StateFlow<String>

    /**
     * The message the user is currently explicitly replying to via the Reply action.
     * If null, sending a message replies to the currentBranchLeafId value.
     */
    val replyTargetMessage: StateFlow<ChatMessage?>

    /**
     * The message currently being edited. Null if no message is being edited.
     */
    val editingMessage: StateFlow<ChatMessage?>

    /**
     * The content of the message being edited.
     */
    val editingContent: StateFlow<String>

    /**
     * Whether a message is currently being sent.
     */
    val isSendingMessage: StateFlow<Boolean>

    /**
     * The current dialog state for the chat area (e.g., delete confirmation).
     */
    val dialogState: StateFlow<ChatAreaDialogState>

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

    // --- State Mutation Methods ---

    /**
     * Sets the active session ID, which triggers reactive state updates.
     */
    fun setActiveSessionId(sessionId: Long?)

    /**
     * Sets the input content.
     */
    fun setInputContent(content: String)

    /**
     * Sets the reply target message.
     */
    fun setReplyTarget(message: ChatMessage?)

    /**
     * Sets the editing message.
     */
    fun setEditingMessage(message: ChatMessage?)

    /**
     * Sets the editing content.
     */
    fun setEditingContent(content: String)

    /**
     * Sets the sending message flag.
     */
    fun setIsSending(isSending: Boolean)

    /**
     * Sets the dialog state.
     */
    fun setDialogState(dialogState: ChatAreaDialogState)

    /**
     * Cancels/closes any dialog by setting state to None.
     */
    fun cancelDialog()

    /**
     * Sets retry state for failed operations.
     */
    fun setRetryState(sessionId: Long?, eventId: String?)

    /**
     * Clears retry state.
     */
    fun clearRetryState()

    /**
     * Resets the entire chat state to its initial state.
     */
    fun resetState()
}
