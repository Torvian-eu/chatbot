package eu.torvian.chatbot.app.viewmodel.chat

import androidx.lifecycle.ViewModel
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolCallsMap
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.usecase.*
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
 * @param selectModelUC Use case for selecting models
 * @param selectSettingsUC Use case for selecting settings
 * @param updateInputUC Use case for updating input content
 * @param copyToClipboardUC Use case for copying content to clipboard
 * @param toggleToolsUC Use case for toggling tools for sessions
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
    private val selectModelUC: SelectModelUseCase,
    private val selectSettingsUC: SelectSettingsUseCase,
    private val updateInputUC: UpdateInputUseCase,
    private val copyToClipboardUC: CopyToClipboardUseCase,
    private val toggleToolsUC: ToggleToolsUseCase,
    private val normalScope: CoroutineScope,
    private val backgroundScope: CoroutineScope
) : ViewModel(normalScope) {

    /**
     * Job tracking the currently active message sending operation.
     * Null when no message is being sent.
     */
    private var sendMessageJob: Job? = null

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
     * The list of settings profiles available for the currently selected model.
     */
    val availableSettingsForCurrentModel: StateFlow<DataState<RepositoryError, List<ModelSettings>>> =
        state.availableSettingsForCurrentModel

    /**
     * The list of tools enabled for the current session.
     */
    val enabledToolsForCurrentSession: StateFlow<DataState<RepositoryError, List<ToolDefinition>>> =
        state.enabledToolsForCurrentSession

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
     * Indicates whether a message is currently in the process of being sent.
     */
    val isSendingMessage: StateFlow<Boolean> = state.isSendingMessage

    /**
     * The current dialog state for the chat area (e.g., delete confirmation).
     */
    val dialogState: StateFlow<ChatAreaDialogState> = state.dialogState

    // --- Public Action Functions (Delegated to Use Cases) ---

    /**
     * Loads a chat session and its messages by ID.
     * Resets all state before loading the new session.
     */
    fun loadSession(sessionId: Long, userId: Long) {
        normalScope.launch {
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
     * Sends the current message content to the active session, or continues from a specific message.
     *
     * @param continueFromMessage When provided, uses Branch & Continue mode: sends null content
     *                            with this message's ID as parentMessageId to continue the conversation
     *                            from that point. When null, sends the current input content normally.
     */
    fun sendMessage(continueFromMessage: ChatMessage? = null) {
        sendMessageJob = normalScope.launch {
            try {
                sendMessageUC.execute(continueFromMessage = continueFromMessage)
            } finally {
                sendMessageJob = null
            }
        }
    }

    /**
     * Cancels the currently active message sending operation.
     */
    fun cancelSendMessage() {
        sendMessageJob?.cancel()
        sendMessageJob = null
        state.setIsSending(false)
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
        normalScope.launch {
            deleteMessageUC.execute(messageId)
        }
    }

    /**
     * Deletes a specific message and all its replies recursively.
     */
    private fun deleteMessageRecursively(messageId: Long) {
        normalScope.launch {
            deleteMessageUC.execute(messageId, recursive = true)
        }
    }

    /**
     * Switches the currently displayed chat branch to the one that includes the given message ID.
     */
    fun switchBranchToMessage(targetMessageId: Long) {
        normalScope.launch {
            switchBranchUC.execute(targetMessageId)
        }
    }

    /**
     * Selects a model for the current session.
     */
    fun selectModel(modelId: Long?) {
        normalScope.launch {
            selectModelUC.execute(modelId)
        }
    }

    /**
     * Selects settings for the current session.
     */
    fun selectSettings(settingsId: Long?) {
        normalScope.launch {
            selectSettingsUC.execute(settingsId)
        }
    }

    // --- Tool Management ---

    /**
     * Toggles a tool for the current session.
     */
    fun toggleToolForSession(toolDefinition: ToolDefinition, enabled: Boolean) {
        normalScope.launch {
            toggleToolsUC.toggleTool(toolDefinition, enabled)
        }
    }

    /**
     * Batch toggles multiple tools for the current session.
     */
    fun toggleToolsForSession(toolDefinitions: List<ToolDefinition>, enabled: Boolean) {
        normalScope.launch {
            toggleToolsUC.toggleTools(toolDefinitions, enabled)
        }
    }

    /**
     * Approves a tool call and updates its status to EXECUTING.
     */
    private fun approveToolCall(toolCall: ToolCall) {
        backgroundScope.launch {
            sendMessageUC.approveToolCall(toolCall.id)
        }
    }

    /**
     * Denies a tool call and updates its status to USER_DENIED.
     */
    private fun denyToolCall(toolCall: ToolCall, reason: String?) {
        backgroundScope.launch {
            sendMessageUC.denyToolCall(toolCall.id, reason)
        }
    }

    // --- Dialog Management ---

    /**
     * Shows the tool configuration dialog for the current session.
     */
    fun showToolConfigDialog() {
        state.setDialogState(
            ChatAreaDialogState.ToolConfig(
                enabledToolsFlow = state.enabledToolsForCurrentSession,
                availableToolsFlow = state.availableTools,
                mcpServersFlow = state.mcpServers,
                onToggleTool = { toolDefinition, isEnabled ->
                    toggleToolForSession(toolDefinition, isEnabled)
                },
                onToggleTools = { toolDefinitions, isEnabled ->
                    toggleToolsForSession(toolDefinitions, isEnabled)
                },
                onDismiss = {
                    state.setDialogState(ChatAreaDialogState.None)
                }
            )
        )
    }

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
     * Shows the delete message confirmation dialog with pre-bound actions.
     * This is called when the user signals an intent to delete.
     */
    fun requestDeleteMessage(message: ChatMessage) {
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
        state.setDialogState(ChatAreaDialogState.InsertMessage(
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
