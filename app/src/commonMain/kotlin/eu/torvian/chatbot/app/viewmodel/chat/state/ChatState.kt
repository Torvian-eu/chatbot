package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.chat.search.MessageSearchMatch
import eu.torvian.chatbot.app.chat.search.SearchDirection
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolCallsMap
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
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
     * The list of agent roles available for session selection, used by the top-bar role selector.
     * Filtered to roles that are **not disabled for the current user**: disabled roles drop out of
     * the selector and of the [currentAgentRole] derivation, so a session attached to a disabled
     * role resolves as inert ("No role", composer gated). This is a filtered view of the repository
     * stream; the settings tab reads the unfiltered stream so disabled roles stay re-enableable.
     */
    val availableAgentRoles: StateFlow<DataState<RepositoryError, List<AgentRoleDto>>>

    /**
     * The list of all available tool definitions.
     * Filtered to show only globally enabled tools.
     */
    val availableTools: StateFlow<DataState<RepositoryError, List<ToolDefinition>>>

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
    val mcpServers: StateFlow<DataState<RepositoryError, List<LocalMCPServerDto>>>

    // --- Derived Lookup Maps (for performance & graceful degradation) ---
    /**
     * A map of model IDs to LLMModel objects, derived from `availableModels`.
     * This is optimized for quick lookups (e.g., rendering message metadata).
     * It will be an empty map if models are loading or failed to load.
     */
    val modelsById: StateFlow<Map<Long, LLMModel>>

    /**
     * A map of settings IDs to [ModelSettings] objects, derived from the global settings list.
     * Optimized for quick lookups.
     */
    val settingsById: StateFlow<Map<Long, ModelSettings>>

    // --- Derived "Current Item" States (for UI convenience) ---
    /**
     * The currently active ChatSession object, or null if not loaded.
     * Derived from sessionDataState.
     */
    val currentSession: StateFlow<ChatSession?>

    /**
     * The agent role currently selected for the active session, or null when no role is attached
     * or the referenced role cannot be resolved from [availableAgentRoles].
     * Derived from `currentSession.agentRoleId` and the role list.
     */
    val currentAgentRole: StateFlow<AgentRoleDto?>

    /**
     * The fully resolved LLMModel object for the current session, or null.
     * Derived from the role attached to the session ([currentAgentRole]) and [modelsById].
     * A session no longer stores its own model id, so this is the model bundled by the role.
     */
    val currentModel: StateFlow<LLMModel?>

    /**
     * The fully resolved [ModelSettings] object for the current session, or null.
     * Derived from the role attached to the session ([currentAgentRole]) and [settingsById].
     * A session no longer stores its own settings id, so this is the settings bundled by the role.
     */
    val currentSettings: StateFlow<ModelSettings?>

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
     * Lifecycle state used by the composer action button.
     */
    val turnExecutionState: StateFlow<TurnExecutionState>

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

    // --- In-Session Search State Properties ---

    /**
     * Whether the in-session search UI should currently be visible.
     */
    val isSearchActive: StateFlow<Boolean>

    /**
     * Current query applied to the displayed messages.
     */
    val searchQuery: StateFlow<String>

    /**
     * Occurrence-level matches derived from the displayed messages and [searchQuery].
     */
    val searchResults: StateFlow<List<MessageSearchMatch>>

    /**
     * Currently selected result index, or `-1` when no match is selectable.
     */
    val currentSearchIndex: StateFlow<Int>

    /**
     * Previously displayed thread that can be restored after search-driven branch switching.
     */
    val rollbackTarget: StateFlow<Long?>

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
     * Updates the lifecycle state of the active turn.
     *
     * @param executionState New state to expose to observers.
     */
    fun setTurnExecutionState(executionState: TurnExecutionState)

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

    // --- In-Session Search State Mutation Methods ---

    /**
     * Shows the in-session search UI without changing the current query.
     */
    fun showSearch()

    /**
     * Closes the in-session search UI and clears the active query and selection.
     *
     * The rollback state is intentionally preserved so users can still return after dismissing search
     * and reopening it in the same session context.
     */
    fun closeSearch()

    /**
     * Replaces the active query and resets the selected occurrence.
     *
     * The resulting occurrence list is re-derived from the currently displayed messages.
     *
     * @param query New query entered by the user.
     */
    fun updateSearchQuery(query: String)

    /**
     * Moves the selected occurrence forward or backward through the current result set.
     *
     * @param direction Requested navigation direction.
     */
    fun navigateSearchResult(direction: SearchDirection)

    /**
     * Selects a concrete occurrence index directly.
     *
     * @param index Zero-based result index requested by the UI.
     */
    fun jumpToSearchResult(index: Int)

    /**
     * Sets the rollback target for returning to a previous thread.
     *
     * @param targetMessageId The rollback target message ID, or null to clear.
     */
    fun setRollbackTarget(targetMessageId: Long?)

    /**
     * Sets a pending target message ID to select once search results are computed.
     *
     * This enables declarative targeting: when a navigation intent arrives, the target
     * message ID is registered here, and the search result derivation flow will
     * automatically select it when results are available.
     *
     * @param messageId The message ID to select, or null to clear.
     */
    fun setPendingSearchMessageTarget(messageId: Long?)
}
