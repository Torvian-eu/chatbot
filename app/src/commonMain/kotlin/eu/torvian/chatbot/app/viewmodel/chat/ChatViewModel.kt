package eu.torvian.chatbot.app.viewmodel.chat

import androidx.lifecycle.ViewModel
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.app.domain.events.SnackbarInteractionEvent
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.usecase.DeleteMessageUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.EditMessageUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.LoadSessionUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.ReplyUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SelectModelUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SelectSettingsUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SendMessageUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SwitchBranchUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.UpdateInputUseCase
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatSessionData
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
    private val eventBus: EventBus,
    private val normalScope: CoroutineScope,
    private val backgroundScope: CoroutineScope
) : ViewModel(normalScope) {

    private val logger = kmpLogger<ChatViewModel>()

    // --- Public State Properties (delegated to SharedChatState) ---

    /**
     * The state of the currently loaded chat session combined with its model settings.
     * When in Success state, provides the ChatSessionData object containing both session and settings.
     */
    val sessionDataState: StateFlow<UiState<ApiError, ChatSessionData>> = state.sessionDataState

    /**
     * The ID of the leaf message in the currently displayed thread branch.
     */
    val currentBranchLeafId: StateFlow<Long?> = state.currentBranchLeafId

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
    fun loadSession(sessionId: Long, forceReload: Boolean = false) {
        normalScope.launch {
            loadSessionUC.execute(sessionId, forceReload)
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
     * Deletes a specific message and its children from the session.
     */
    fun deleteMessage(messageId: Long) {
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

    /**
     * Cancels all coroutines when the ViewModel is cleared.
     */
    override fun onCleared() {
        super.onCleared()
        backgroundScope.cancel()
        normalScope.cancel()
    }
}