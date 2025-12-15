package eu.torvian.chatbot.app.viewmodel.chat

import androidx.lifecycle.ViewModel
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.events.SnackbarInteractionEvent
import eu.torvian.chatbot.app.repository.LocalMCPServerRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolCallsMap
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.usecase.*
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import kotlinx.coroutines.CoroutineScope
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
 * @param switchBranchUC Use case for switching branches
 * @param selectModelUC Use case for selecting models
 * @param selectSettingsUC Use case for selecting settings
 * @param updateInputUC Use case for updating input content
 * @param toolRepository Repository for tool management operations
 * @param mcpServerRepository Repository for MCP server configurations
 * @param errorNotifier Notifier for error handling
 * @param eventBus Event bus for cross-cutting concerns
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
    private val switchBranchUC: SwitchBranchUseCase,
    private val selectModelUC: SelectModelUseCase,
    private val selectSettingsUC: SelectSettingsUseCase,
    private val updateInputUC: UpdateInputUseCase,
    private val toolRepository: ToolRepository,
    private val mcpServerRepository: LocalMCPServerRepository,
    private val errorNotifier: ErrorNotifier,
    private val eventBus: EventBus,
    private val normalScope: CoroutineScope,
    private val backgroundScope: CoroutineScope
) : ViewModel(normalScope) {

    private val logger = kmpLogger<ChatViewModel>()

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
     * The list of all available tool definitions.
     */
    val availableTools: StateFlow<DataState<RepositoryError, List<ToolDefinition>>> = state.availableTools

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
     * A map of settings IDs to ModelSettings objects for quick lookups.
     */
    val settingsById: StateFlow<Map<Long, ModelSettings>> = state.settingsById

    /**
     * The currently active ChatSession object, or null if not loaded.
     */
    val currentSession: StateFlow<ChatSession?> = state.currentSession

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

    init {
        // Handle retry functionality via EventBus using background scope
        backgroundScope.launch {
            eventBus.events.collect { event ->
                if (event is SnackbarInteractionEvent && event.isActionPerformed) {
                    val handled = loadSessionUC.handleRetry(event.originalAppEventId)
                    if (handled) {
                        logger.info("Handled retry for event ${event.originalAppEventId}")
                    }
                }
            }
        }
    }

    // --- Public Action Functions (Delegated to Use Cases) ---

    /**
     * Loads a chat session and its messages by ID.
     */
    fun loadSession(sessionId: Long, userId: Long, forceReload: Boolean = false) {
        normalScope.launch {
            loadSessionUC.execute(sessionId, userId, forceReload)
        }
    }

    /**
     * Clears the current session state.
     */
    fun clearSession() {
        state.resetState()
    }

    /**
     * Updates the input content.
     */
    fun updateInput(text: String) {
        updateInputUC.execute(text)
    }

    /**
     * Sends the current message content to the active session.
     */
    fun sendMessage() {
        normalScope.launch {
            sendMessageUC.execute()
        }
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
     * Cancels the message editing state.
     */
    fun cancelEditing() {
        editMessageUC.cancel()
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
     * Loads all available tools.
     */
    fun loadTools() {
        normalScope.launch {
            toolRepository.loadTools().mapLeft { error ->
                errorNotifier.repositoryError(
                    error = error,
                    shortMessage = "Failed to load tools"
                )
            }
        }
    }

    /**
     * Loads tools enabled for the current session.
     */
    fun loadToolsForSession() {
        val sessionId = state.activeSessionId.value ?: return
        normalScope.launch {
            toolRepository.loadEnabledToolsForSession(sessionId).mapLeft { error ->
                errorNotifier.repositoryError(
                    error = error,
                    shortMessage = "Failed to load session tools"
                )
            }
        }
    }

    /**
     * Toggles a tool for the current session.
     */
    fun toggleToolForSession(toolDefinition: ToolDefinition, enabled: Boolean) {
        val sessionId = state.activeSessionId.value ?: return
        normalScope.launch {
            toolRepository.setToolEnabledForSession(sessionId, toolDefinition, enabled).mapLeft { error ->
                errorNotifier.repositoryError(
                    error = error,
                    shortMessage = "Failed to toggle tool"
                )
            }
        }
    }

    /**
     * Batch toggles multiple tools for the current session.
     */
    fun toggleToolsForSession(toolDefinitions: List<ToolDefinition>, enabled: Boolean) {
        val sessionId = state.activeSessionId.value ?: return
        normalScope.launch {
            toolRepository.setToolsEnabledForSession(sessionId, toolDefinitions, enabled).mapLeft { error ->
                errorNotifier.repositoryError(
                    error = error,
                    shortMessage = "Failed to toggle tools"
                )
            }
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
                mcpServersFlow = mcpServerRepository.servers,
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
     */
    fun showToolCallDetails(toolCall: eu.torvian.chatbot.common.models.tool.ToolCall) {
        state.setDialogState(
            ChatAreaDialogState.ToolCallDetails(
                toolCall = toolCall,
                onDismiss = {
                    state.setDialogState(ChatAreaDialogState.None)
                }
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
}