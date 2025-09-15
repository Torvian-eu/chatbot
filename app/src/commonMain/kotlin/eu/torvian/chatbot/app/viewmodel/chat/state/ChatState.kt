package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatModelSettings
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.LLMModel
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
     * The state of the currently loaded chat session.
     */
    val sessionDataState: StateFlow<DataState<RepositoryError, ChatSession>>

    /**
     * The list of all currently configured LLM models available for selection.
     */
    val availableModels: StateFlow<DataState<RepositoryError, List<LLMModel>>>

    /**
     * The list of settings profiles available for the currently selected model.
     */
    val availableSettingsForCurrentModel: StateFlow<DataState<RepositoryError, List<ChatModelSettings>>>

    // --- Derived Lookup Maps (for performance & graceful degradation) ---
    /**
     * A map of model IDs to LLMModel objects, derived from `availableModels`.
     * This is optimized for quick lookups (e.g., rendering message metadata).
     * It will be an empty map if models are loading or failed to load.
     */
    val modelsById: StateFlow<Map<Long, LLMModel>>

    /**
     * A map of settings IDs to ChatModelSettings objects, derived from the global settings list.
     * Optimized for quick lookups.
     */
    val settingsById: StateFlow<Map<Long, ChatModelSettings>>

    // --- Derived "Current Item" States (for UI convenience) ---
    /**
     * The currently active ChatSession object, or null if not loaded.
     * Derived from sessionDataState.
     */
    val currentSession: StateFlow<ChatSession?>

    /**
     * The fully resolved LLMModel object for the current session, or null.
     * Derived by combining currentSession and modelsById.
     */
    val currentModel: StateFlow<LLMModel?>

    /**
     * The fully resolved ChatModelSettings object for the current session, or null.
     * Derived by combining currentSession and settingsById.
     */
    val currentSettings: StateFlow<ChatModelSettings?>

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
