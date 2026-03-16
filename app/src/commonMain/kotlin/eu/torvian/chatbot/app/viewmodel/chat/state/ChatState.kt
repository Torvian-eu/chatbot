package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolCallsMap
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.tool.ToolDefinition
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

    /**
     * The list of all available tool definitions.
     * Filtered to show only globally enabled tools.
     */
    val availableTools: StateFlow<DataState<RepositoryError, List<ToolDefinition>>>

    /**
     * The list of tools enabled for the current session.
     * Returns empty list if no session is active.
     */
    val enabledToolsForCurrentSession: StateFlow<DataState<RepositoryError, List<ToolDefinition>>>

    /**
     * Tool calls for the current session, organized by message ID.
     * Map structure: messageId -> List<ToolCall>
     * Returns empty map if no session is active or no tool calls exist.
     */
    val toolCallsForCurrentSession: StateFlow<DataState<RepositoryError, ToolCallsMap>>

    /**
     * MCP server configurations for the current user.
     * Used for displaying server information in tool configuration dialogs.
     */
    val mcpServers: StateFlow<DataState<RepositoryError, List<LocalMCPServer>>>

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
     * Message IDs currently rendered as collapsed for the active session.
     *
     * This value is derived from current session messages and in-memory user toggles.
     */
    val collapsedMessageIds: StateFlow<Set<Long>>

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
     * File references for the message being edited.
     * Separate from pendingFileReferences (used for new messages).
     */
    val editingFileReferences: StateFlow<List<FileReference>>

    /**
     * Base path override for file references when editing a message.
     * Separate from basePathOverride (used for new messages).
     */
    val editingBasePathOverride: StateFlow<String?>

    /**
     * Whether a message is currently being sent.
     */
    val isSendingMessage: StateFlow<Boolean>

    /**
     * The current dialog state for the chat area (e.g., delete confirmation).
     */
    val dialogState: StateFlow<ChatAreaDialogState>

    /**
     * File references attached to the current message being composed.
     * These will be included with the message when sent.
     */
    val pendingFileReferences: StateFlow<List<FileReference>>

    /**
     * Override for the base path used when creating file references.
     * When null, the common parent path of selected files is used.
     * Stored per session in the ViewModel, not persisted to server.
     */
    val basePathOverride: StateFlow<String?>


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
     * Toggles whether a message should be collapsed in the UI.
     */
    fun toggleMessageCollapsed(messageId: Long)

    /**
     * Collapses all currently displayed messages in the UI.
     */
    fun collapseAllDisplayedMessages()

    /**
     * Expands all currently displayed messages in the UI.
     */
    fun expandAllDisplayedMessages()

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
     * Sets the editing file references.
     */
    fun setEditingFileReferences(fileReferences: List<FileReference>)

    /**
     * Updates the editing file references list by applying a transformation function.
     */
    fun updateEditingFileReferences(transform: (List<FileReference>) -> List<FileReference>)

    /**
     * Sets the base path override for editing file references.
     */
    fun setEditingBasePathOverride(path: String?)

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
     * Updates the file references list by applying a transformation function.
     * This is the primary method for modifying file references.
     * Can be used to add, remove, or clear file references via the transform function.
     *
     * @param transform Function that transforms the current list of file references
     */
    fun updateFileReferences(transform: (List<FileReference>) -> List<FileReference>)

    /**
     * Sets the base path override for file references.
     */
    fun setBasePathOverride(path: String?)

    /**
     * Resets the entire chat state to its initial state.
     */
    fun resetState()
}
