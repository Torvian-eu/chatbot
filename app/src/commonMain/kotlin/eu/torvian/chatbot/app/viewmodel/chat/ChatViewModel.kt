package eu.torvian.chatbot.app.viewmodel.chat

import androidx.lifecycle.ViewModel
import eu.torvian.chatbot.app.chat.search.MessageSearchMatch
import eu.torvian.chatbot.app.chat.search.SearchDirection
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolCallsMap
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.SearchNavigationIntent
import eu.torvian.chatbot.app.viewmodel.SearchNavigationState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import eu.torvian.chatbot.app.viewmodel.chat.usecase.*
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the UI state for the main chat area of the currently active session.
 *
 * This refactored ViewModel delegates all business logic to use cases and exposes
 * state from SharedChatState. It serves as a thin coordination layer between
 * the UI and the domain logic.
 *
 * Note: The coroutine scopes normalScope and backgroundScope are the same when used in production. They only differ in tests.
 *
 * @param state The shared chat state holder
 * @param loadSessionUC Use case for loading sessions
 * @param sendMessageUC Use case for sending messages
 * @param replyUC Use case for reply functionality
 * @param editMessageUC Use case for editing messages
 * @param deleteMessageUC Use case for deleting messages
 * @param insertMessageUC Use case for inserting messages
 * @param switchBranchUC Use case for switching branches
 * @param selectAgentRoleUC Use case for selecting agent roles
 * @param loadAgentRolesUC Use case for loading the user's agent roles
 * @param updateInputUC Use case for updating input content
 * @param copyToClipboardUC Use case for copying content to clipboard
 * @param fileReferenceUC Use case for managing file references
 * @param navigationState State holder for cross-session search navigation intent.
 * @param normalScope Coroutine scope for UI operations
 * @param backgroundScope Coroutine scope for background operations (should only differ from normalScope in tests)
 */
class ChatViewModel(
    private val state: ChatState,
    private val loadSessionUC: LoadSessionUseCase,
    private val sendMessageUC: SendMessageUseCase,
    private val replyUC: ReplyUseCase,
    private val editMessageUC: EditMessageUseCase,
    private val deleteMessageUC: DeleteMessageUseCase,
    private val insertMessageUC: InsertMessageUseCase,
    private val switchBranchUC: SwitchBranchUseCase,
    private val selectAgentRoleUC: SelectAgentRoleUseCase,
    private val loadAgentRolesUC: LoadAgentRolesUseCase,
    private val updateInputUC: UpdateInputUseCase,
    private val copyToClipboardUC: CopyToClipboardUseCase,
    private val fileReferenceUC: FileReferenceUseCase,
    private val navigationState: SearchNavigationState,
    private val normalScope: CoroutineScope,
    private val backgroundScope: CoroutineScope
) : ViewModel(normalScope) {

    companion object {
        private val logger = kmpLogger<ChatViewModel>()

        /** Maximum time allowed for the server to publish cancellation events before a hard cancel. */
        private const val CANCELLATION_DRAIN_TIMEOUT_MILLIS = 3_000L
    }

    /**
     * Job tracking the currently active message sending operation.
     * Null when no message is being sent.
     */
    private var sendMessageJob: Job? = null

    /** Job that bounds the time spent waiting for the server to acknowledge cancellation. */
    private var cancellationDrainJob: Job? = null

    /**
     * Whether an assistant turn is currently in progress (RUNNING, PAUSING, or STOPPING).
     *
     * Conversation-mutating and turn-starting actions must not run while a turn is active.
     * This is a defense-in-depth guard behind the disabled UI controls: even if a caller
     * bypasses the UI, no conflicting generation or destructive edit can start mid-turn.
     */
    private val isTurnActive: Boolean
        get() = state.turnExecutionState.value != TurnExecutionState.IDLE

    // --- Public State Properties (delegated to Reactive ChatState) ---

    /**
     * The ID of the currently active session.
     */
    val activeSessionId: StateFlow<Long?> = state.activeSessionId

    /**
     * The state of the currently loaded chat session.
     */
    val sessionDataState: StateFlow<DataState<RepositoryError, ChatSession>> = state.sessionDataState

    /**
     * The list of all currently configured LLM models available for selection.
     */
    val availableModels: StateFlow<DataState<RepositoryError, List<LLMModel>>> = state.availableModels

    /**
     * The list of agent roles owned by the current user, for the top-bar role selector.
     */
    val availableAgentRoles: StateFlow<DataState<RepositoryError, List<AgentRoleDto>>> = state.availableAgentRoles

    /**
     * Tool calls for the current session, organized by message ID.
     */
    val toolCallsForCurrentSession: StateFlow<DataState<RepositoryError, ToolCallsMap>> =
        state.toolCallsForCurrentSession

    /**
     * A map of model IDs to LLMModel objects for quick lookups.
     */
    val modelsById: StateFlow<Map<Long, LLMModel>> = state.modelsById

    /**
     * The agent role currently selected for the active session, or null when no role is attached.
     */
    val currentAgentRole: StateFlow<AgentRoleDto?> = state.currentAgentRole

    /**
     * The fully resolved LLMModel object for the current session, or null.
     */
    val currentModel: StateFlow<LLMModel?> = state.currentModel

    /**
     * The fully resolved ModelSettings object for the current session, or null.
     */
    val currentSettings: StateFlow<ModelSettings?> = state.currentSettings

    /**
     * The list of messages to display in the UI, representing the currently selected thread branch.
     */
    val displayedMessages: StateFlow<List<ChatMessage>> = state.displayedMessages

    /**
     * Message IDs that should render in collapsed mode.
     */
    val collapsedMessageIds: StateFlow<Set<Long>> = state.collapsedMessageIds

    /**
     * The current text content in the message input field.
     */
    val inputContent: StateFlow<String> = state.inputContent

    /**
     * The message the user is currently explicitly replying to via the Reply action.
     */
    val replyTargetMessage: StateFlow<ChatMessage?> = state.replyTargetMessage

    /**
     * The message currently being edited. Null if no message is being edited.
     */
    val editingMessage: StateFlow<ChatMessage?> = state.editingMessage

    /**
     * The content of the message currently being edited.
     */
    val editingContent: StateFlow<String> = state.editingContent

    /**
     * File references for the message currently being edited.
     */
    val editingFileReferences: StateFlow<List<FileReference>> = state.editingFileReferences

    /**
     * Base path override for the message currently being edited.
     */
    val editingBasePathOverride: StateFlow<String?> = state.editingBasePathOverride

    /**
     * Lifecycle state used by the chat input action button.
     */
    val turnExecutionState: StateFlow<TurnExecutionState> = state.turnExecutionState

    /**
     * The current dialog state for the chat area (e.g., delete confirmation).
     */
    val dialogState: StateFlow<ChatAreaDialogState> = state.dialogState

    /**
     * File references attached to the current message being composed.
     */
    val pendingFileReferences: StateFlow<List<FileReference>> = state.pendingFileReferences

    // --- In-Session Search State Properties ---

    /**
     * Whether the in-session search UI should currently be visible.
     */
    val isSearchActive: StateFlow<Boolean> = state.isSearchActive

    /**
     * Current query applied to the displayed messages.
     */
    val searchQuery: StateFlow<String> = state.searchQuery

    /**
     * Occurrence-level matches derived from the displayed messages and [searchQuery].
     */
    val searchResults: StateFlow<List<MessageSearchMatch>> = state.searchResults

    /**
     * Currently selected result index, or `-1` when no match is selectable.
     */
    val currentSearchIndex: StateFlow<Int> = state.currentSearchIndex

    /**
     * Previously displayed thread that can be restored after search-driven branch switching.
     */
    val rollbackTarget: StateFlow<Long?> = state.rollbackTarget

    /**
     * Whether the UI should currently offer the lightweight rollback action.
     */
    val canReturnToPreviousThread: Boolean
        get() = isSearchActive.value &&
                rollbackTarget.value != null

    init {
        // Observe navigation intent and react when this VM is active for the target session
        // and the session data is loaded. This handles all cases:
        // - Intent arrives before session load completes
        // - Session load completes after intent is already present
        // - Session becomes active later
        combine(
            navigationState.intent,
            activeSessionId,
            sessionDataState
        ) { intent, activeId, sessionData ->
            Triple(intent, activeId, sessionData)
        }
            .filter { (intent, activeId, sessionData) ->
                intent != null && activeId == intent.sessionId && sessionData is DataState.Success
            }
            .onEach { (intent, _, _) ->
                intent?.let { safeIntent ->
                    processNavigationIntent(safeIntent)
                }
            }
            .launchIn(backgroundScope)
    }

    /**
     * Processes a navigation intent sequentially, awaiting branch switch before search activation.
     *
     * @param intent The navigation intent to process.
     */
    private suspend fun processNavigationIntent(intent: SearchNavigationIntent) {
        // Get current leaf before any potential branch switch
        val currentLeafBeforeSwitch = (sessionDataState.value as? DataState.Success)?.data?.currentLeafMessageId

        // Check if target message is already visible in current branch
        val isMessageVisible = displayedMessages.value.any { it.id == intent.messageId }

        if (isMessageVisible) {
            // No branch switch needed, no rollback target
            state.setRollbackTarget(null)
        } else {
            // Branch switch needed - capture current leaf as rollback target
            state.setRollbackTarget(currentLeafBeforeSwitch)
            switchBranchUC.execute(intent.messageId)
        }

        // Set the pending target and query - the search result derivation flow will
        // consume the pending target and select the index when results are computed
        state.setPendingSearchMessageTarget(intent.messageId)
        state.updateSearchQuery(intent.query)
        state.showSearch()

        // Clear the intent so it's not reprocessed
        navigationState.clearIntent()

        logger.debug("Processed navigation intent: session ${intent.sessionId}, message ${intent.messageId}")
    }

    // --- Public Action Functions (Delegated to Use Cases) ---

    /**
     * Loads a chat session and its messages by ID.
     * Resets all state before loading the new session.
     *
     * @param sessionId The identifier of the session to load.
     * @param userId The authenticated user's identifier, required for loading user-scoped MCP servers.
     * @return The [Job] that performs the load and completes once the session and its dependencies
     *         (models, settings, roles, tools, tool approval preferences) have been loaded.
     */
    fun loadSession(sessionId: Long, userId: Long): Job {
        return normalScope.launch {
            // Clear all state (shared and use case internal state) before loading
            clearSession()
            loadSessionUC.execute(sessionId, userId)
        }
    }

    /**
     * Clears the currently loaded session and resets all state.
     */
    fun clearSession() {
        state.resetState()
        // Reset use case internal state
        loadSessionUC.resetState()
    }

    /**
     * Updates the input content.
     */
    fun updateInput(text: String) {
        updateInputUC.execute(text)
    }

    /**
     * Toggles collapsed state for a collapsible message.
     */
    fun toggleMessageCollapsed(messageId: Long) {
        state.toggleMessageCollapsed(messageId)
    }

    /**
     * Sends the current message content to the active session, or continues from a specific message.
     *
     * @param continueFromMessage When provided, uses Branch & Continue mode: sends null content
     *            with this message's ID as parentMessageId to continue the conversation
     *            from that point. When null, sends the current input content normally.
     * @return The [Job] that performs the send and completes when the turn ends, or `null` when the
     *         send is refused: a turn is already active, the input is blank in normal mode, or the
     *         session's role/model/settings cannot be resolved. The last two mirror the guards inside
     *         [SendMessageUseCase.execute] so the spawned-turn coordinator can observe a refusal
     *         deterministically instead of awaiting a silently-completed no-op turn.
     */
    fun sendMessage(continueFromMessage: ChatMessage? = null): Job? {
        // Refuse to start a new turn while one is already active. This covers regular sends,
        // Branch & Continue, and any caller that bypasses the disabled UI controls.
        if (isTurnActive) return null
        // Normal-mode sends need non-blank input; the spawned-turn path always sets the input first.
        if (continueFromMessage == null && state.inputContent.value.isBlank()) return null
        // A session role with a resolvable model/settings profile is required for a turn to run.
        // Returning null here lets the spawned-turn executor report a deterministic tool error.
        if (state.currentAgentRole.value == null || state.currentModel.value == null || state.currentSettings.value == null) {
            return null
        }
        val job = normalScope.launch {
            sendMessageUC.execute(continueFromMessage = continueFromMessage)
        }
        sendMessageJob = job
        state.setTurnExecutionState(TurnExecutionState.RUNNING)
        job.invokeOnCompletion {
            sendMessageJob = null
            // STOPPING owns the final transition until the cancellation drain completes.
            if (state.turnExecutionState.value != TurnExecutionState.STOPPING) {
                state.setTurnExecutionState(TurnExecutionState.IDLE)
            }
        }
        return job
    }

    /**
     * Routes the composer action to a soft pause or hard stop according to the active turn state.
     *
     * RUNNING deliberately sends only Pause, while PAUSING sends Cancel and starts the bounded
     * drain. STOPPING is intentionally inert because another click cannot improve cancellation.
     */
    fun handlePauseOrStop() {
        when (state.turnExecutionState.value) {
            TurnExecutionState.RUNNING -> {
                state.setTurnExecutionState(TurnExecutionState.PAUSING)
                normalScope.launch {
                    sendMessageUC.requestPause()
                }
            }

            TurnExecutionState.PAUSING -> {
                state.setTurnExecutionState(TurnExecutionState.STOPPING)
                val activeSendJob = sendMessageJob ?: run {
                    state.setTurnExecutionState(TurnExecutionState.IDLE)
                    return
                }
                if (cancellationDrainJob?.isActive == true) return

                // Keep collecting the socket so terminal CANCELLED events reach the UI before closure.
                cancellationDrainJob = normalScope.launch {
                    sendMessageUC.requestCancellation()
                    withTimeoutOrNull(CANCELLATION_DRAIN_TIMEOUT_MILLIS.milliseconds) {
                        activeSendJob.join()
                    }
                    if (activeSendJob.isActive) {
                        // A broken or stuck peer must not leave the send state active forever.
                        activeSendJob.cancel()
                    }
                }.also { drainJob ->
                    drainJob.invokeOnCompletion {
                        cancellationDrainJob = null
                        state.setTurnExecutionState(TurnExecutionState.IDLE)
                    }
                }
            }

            TurnExecutionState.STOPPING,
            TurnExecutionState.IDLE -> Unit
        }
    }

    /**
     * Compatibility action that now follows the state-machine semantics.
     */
    fun pauseSendMessage() = handlePauseOrStop()

    /**
     * Compatibility action used by callers that previously exposed a dedicated stop callback.
     */
    fun cancelSendMessage() = handlePauseOrStop()

    /**
     * Hard-cancels the in-flight send without the pause/stop state machine.
     *
     * This is the cancellation hook for the spawned-turn coordinator: a spawned send runs in this
     * ViewModel's `normalScope`, so it survives the primary socket closing on its own. When the
     * primary turn ends mid-spawn, the coordinator calls this to stop the spawned turn: it emits a
     * [eu.torvian.chatbot.common.models.api.core.ChatClientEvent.Cancel] so the server cancels the
     * turn on the spawned socket, then cancels the send job so the socket is closed and the turn
     * state falls back to IDLE through the job's completion handler. Deliberately not routed through
     * [handlePauseOrStop], whose RUNNING branch only sends a soft Pause.
     */
    fun forceCancelSend() {
        val activeSendJob = sendMessageJob
        if (activeSendJob == null || !activeSendJob.isActive) return
        normalScope.launch {
            sendMessageUC.requestCancellation()
        }
        activeSendJob.cancel()
    }

    /**
     * Returns the content of the last assistant message in the currently displayed branch.
     *
     * This is the aggregation point for spawned-turn results: after the send job completes, the
     * turn's final assistant message has been applied to the session cache, so this accessor yields
     * the summary the spawned conversation produced without exposing the full message list.
     *
     * @return The content of the last assistant message, or `null` when the branch has none.
     */
    fun lastAssistantMessageContent(): String? =
        displayedMessages.value.filterIsInstance<ChatMessage.AssistantMessage>().lastOrNull()?.content

    /**
     * Regenerates an assistant message by continuing from its parent message.
     * This is functionally equivalent to "Branch & Continue" from the message's parent.
     * If the message has no parent (root message), this operation does nothing.
     *
     * @param message The assistant message to regenerate.
     */
    fun regenerateMessage(message: ChatMessage) {
        // Regenerating starts a new generation; it must be blocked while a turn is active.
        if (isTurnActive) return
        val parentMessageId = message.parentMessageId ?: return // No parent, can't regenerate
        val parentMessage = displayedMessages.value.find { it.id == parentMessageId } ?: return
        sendMessage(continueFromMessage = parentMessage)
    }

    /**
     * Sets the state to indicate the user is replying to a specific message.
     */
    fun startReplyTo(message: ChatMessage) {
        replyUC.start(message)
    }

    /**
     * Cancels the specific reply target.
     */
    fun cancelReply() {
        replyUC.cancel()
    }

    /**
     * Sets the state to indicate a message is being edited.
     */
    fun startEditing(message: ChatMessage) {
        editMessageUC.start(message)
    }

    /**
     * Updates the content of the message currently being edited.
     */
    fun updateEditingContent(newText: String) {
        editMessageUC.updateContent(newText)
    }

    /**
     * Saves the edited message content.
     */
    fun saveEditing() {
        normalScope.launch {
            editMessageUC.save()
        }
    }

    /**
     * Saves the edited message content as a new copy (sibling).
     */
    fun saveEditingAsCopy() {
        normalScope.launch {
            editMessageUC.saveAsCopy()
        }
    }

    /**
     * Cancels the message editing state.
     */
    fun cancelEditing() {
        editMessageUC.cancel()
    }

    /**
     * Copies the content of a message to the system clipboard.
     *
     * @param message The message whose content should be copied.
     */
    fun copyMessageToClipboard(message: ChatMessage) {
        normalScope.launch {
            copyToClipboardUC.copyMessage(message)
        }
    }

    /**
     * Copies the entire currently displayed message thread to the system clipboard.
     * Messages are formatted with role labels and separated by double newlines.
     */
    fun copyThreadToClipboard() {
        normalScope.launch {
            copyToClipboardUC.copyThread()
        }
    }

    /**
     * Deletes a specific message.
     */
    private fun deleteMessage(messageId: Long) {
        // Deleting while a generation is streaming could corrupt the active conversation.
        if (isTurnActive) return
        normalScope.launch {
            deleteMessageUC.execute(messageId)
        }
    }

    /**
     * Deletes a specific message and all its replies recursively.
     */
    private fun deleteMessageRecursively(messageId: Long) {
        // Deleting while a generation is streaming could corrupt the active conversation.
        if (isTurnActive) return
        normalScope.launch {
            deleteMessageUC.execute(messageId, recursive = true)
        }
    }

    /**
     * Switches the currently displayed chat branch to the one that includes the given message ID.
     *
     * @return Job that can be used to wait for the branch switch to complete.
     */
    fun switchBranchToMessage(targetMessageId: Long): Job {
        return normalScope.launch {
            switchBranchUC.execute(targetMessageId)
        }
    }

    /**
     * Selects an agent role for the current session, or deselects it when null.
     */
    fun selectAgentRole(agentRoleId: Long?) {
        normalScope.launch {
            selectAgentRoleUC.execute(agentRoleId)
        }
    }

    /**
     * Loads the current user's agent roles (used by the top-bar retry action).
     */
    fun loadAgentRoles() {
        normalScope.launch {
            loadAgentRolesUC.execute()
        }
    }

    // --- In-Session Search Actions ---

    /**
     * Shows the in-session search UI without changing the current query.
     */
    fun showSearch() {
        state.showSearch()
    }

    /**
     * Closes the in-session search UI and clears the active query and selection.
     *
     * The rollback state is intentionally preserved so users can still return after dismissing search
     * and reopening it in the same session context.
     */
    fun closeSearch() {
        state.closeSearch()
    }

    /**
     * Replaces the active query and resets the selected occurrence.
     *
     * The resulting occurrence list is re-derived from the currently displayed messages.
     *
     * @param query New query entered by the user.
     */
    fun updateSearchQuery(query: String) {
        state.updateSearchQuery(query)
    }

    /**
     * Moves the selected occurrence forward or backward through the current result set.
     *
     * @param direction Requested navigation direction.
     */
    fun navigateSearchResult(direction: SearchDirection) {
        state.navigateSearchResult(direction)
    }

    /**
     * Selects a concrete occurrence index directly.
     *
     * @param index Zero-based result index requested by the UI.
     */
    fun jumpToSearchResult(index: Int) {
        state.jumpToSearchResult(index)
    }

    /**
     * Starts restoration of the previously displayed thread when a rollback target is available.
     * Clears the rollback target after initiating the switch, so the button disappears
     * once the rollback action has been used.
     */
    fun returnToPreviousThread() {
        val rollbackTarget = rollbackTarget.value ?: return
        // Clear rollback target immediately so button disappears after use
        state.setRollbackTarget(null)
        switchBranchToMessage(rollbackTarget)
        closeSearch()
    }

    // --- File Reference Management ---

    /**
     * Opens the file picker and adds selected files as file references.
     * Uses the current basePathOverride or the common base path of selected files.
     */
    fun pickAndAddFileReferences() {
        normalScope.launch {
            fileReferenceUC.pickAndAddFiles()
        }
    }

    /**
     * Removes a file reference from the current message being composed.
     */
    fun removeFileReference(fileReference: FileReference) {
        fileReferenceUC.removeFileReference(fileReference)
    }

    // --- Editing File Reference Management ---

    /**
     * Opens the file picker and adds selected files to the message being edited.
     */
    fun pickAndAddEditingFileReferences() {
        normalScope.launch {
            editMessageUC.pickAndAddFiles()
        }
    }

    /**
     * Removes a file reference from the message being edited.
     */
    fun removeEditingFileReference(fileReference: FileReference) {
        editMessageUC.removeFileReference(fileReference)
    }

    /**
     * Toggles content inclusion for a file reference in the message being edited.
     */
    fun toggleEditingFileContent(fileReference: FileReference, includeContent: Boolean) {
        normalScope.launch {
            editMessageUC.toggleFileContent(fileReference, includeContent)
        }
    }

    /**
     * Sets the base path override for editing file references.
     */
    fun setEditingBasePathOverride(path: String?) {
        normalScope.launch {
            editMessageUC.updateBasePath(path)
        }
    }

    /**
     * Resets the editing base path to the common path of all current editing file references.
     */
    fun resetEditingBasePath() {
        normalScope.launch {
            editMessageUC.resetBasePathToCommonPath()
        }
    }

    // --- Tool Approval ---

    /**
     * Approves a tool call and updates its status to EXECUTING.
     */
    private fun approveToolCall(toolCall: ToolCall) {
        backgroundScope.launch {
            sendMessageUC.approveToolCall(toolCall)
        }
    }

    /**
     * Denies a tool call and updates its status to USER_DENIED.
     */
    private fun denyToolCall(toolCall: ToolCall, reason: String?) {
        backgroundScope.launch {
            sendMessageUC.denyToolCall(toolCall, reason)
        }
    }

    // --- Dialog Management ---
    /**
     * Shows the tool call details dialog.
     * If the tool call is awaiting approval, approval actions will be available.
     */
    fun showToolCallDetails(toolCall: ToolCall) {
        val isAwaitingApproval = toolCall.status == ToolCallStatus.AWAITING_APPROVAL

        state.setDialogState(
            ChatAreaDialogState.ToolCallDetails(
                toolCall = toolCall,
                onDismiss = {
                    state.setDialogState(ChatAreaDialogState.None)
                },
                onApprove = if (isAwaitingApproval) {
                    { approveToolCall(toolCall) }
                } else null,
                onDeny = if (isAwaitingApproval) {
                    { reason -> denyToolCall(toolCall, reason) }
                } else null
            )
        )
    }

    /**
     * Shows the file reference details dialog.
     */
    fun showFileReferenceDetails(fileReference: FileReference) {
        fileReferenceUC.showFileReferenceDetailsDialog(fileReference)
    }

    /**
     * Shows the file references management dialog.
     * Allows users to manage pending file references before sending.
     */
    fun showFileReferencesManagement() {
        fileReferenceUC.showFileReferencesManagementDialog()
    }

    /**
     * Shows the delete message confirmation dialog with pre-bound actions.
     * This is called when the user signals an intent to delete.
     */
    fun requestDeleteMessage(message: ChatMessage) {
        // Do not offer deletion while a turn is active; the UI disables the trigger, but this
        // also guards against callers bypassing the UI or racing an in-flight generation.
        if (isTurnActive) return
        state.setDialogState(
            ChatAreaDialogState.DeleteMessage(
                message = message,
                onDeleteConfirm = {
                    deleteMessage(message.id)
                },
                onDismiss = {
                    cancelDialog()
                }
            ))
    }

    /**
     * Shows the delete thread (recursive) confirmation dialog with pre-bound actions.
     * This is called when the user signals an intent to delete a message and all its replies.
     */
    fun requestDeleteMessageRecursively(message: ChatMessage) {
        // Do not offer thread deletion while a turn is active; see [requestDeleteMessage].
        if (isTurnActive) return
        state.setDialogState(
            ChatAreaDialogState.DeleteMessageRecursively(
                message = message,
                onDeleteConfirm = {
                    deleteMessageRecursively(message.id)
                },
                onDismiss = {
                    cancelDialog()
                }
            ))
    }

    /**
     * Cancels/closes any dialog by setting state to None.
     */
    fun cancelDialog() {
        state.cancelDialog()
    }

    /**
     * Cancels all coroutines when the ViewModel is cleared.
     */
    override fun onCleared() {
        super.onCleared()
        backgroundScope.cancel()
        normalScope.cancel()
    }

    /**
     * Handles the request to insert a message (show dialog with pre-bound actions).
     */
    fun onRequestInsertMessage(message: ChatMessage) {
        // Do not offer insertion while a turn is active; the UI disables the trigger, but this
        // also guards against callers bypassing the UI or racing an in-flight generation.
        if (isTurnActive) return
        state.setDialogState(
            ChatAreaDialogState.InsertMessage(
            targetMessage = message,
            onConfirm = { position, role, content ->
                confirmInsertMessage(message.id, position, role, content)
            },
            onDismiss = { cancelDialog() }
        ))
    }

    /**
     * Confirms the insertion of a new message at a specific position.
     */
    private fun confirmInsertMessage(
        targetMessageId: Long,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String
    ) {
        // Inserting while a generation is streaming could corrupt the active conversation.
        if (isTurnActive) return
        cancelDialog()
        insertMessageUC.execute(
            scope = normalScope,
            targetMessageId = targetMessageId,
            position = position,
            role = role,
            content = content
        )
    }
}
