package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Manages the UI state for the main chat area of the currently active session,
 * using KMP ViewModel, StateFlow, Arrow's Either, and UiState for loading states.
 *
 * This class is responsible for:
 * - Holding the state (Idle/Loading/Success/Error) of the current session and its messages.
 * - Structuring the flat list of messages into a threaded view for display using Flows.
 * - Managing the state of the message input area.
 * - Handling user actions like sending messages, replying, editing, and deleting messages.
 * - Communicating with the backend via the ChatApi and SessionApi, handling their Either results.
 * - Managing the currently displayed thread branch based on the session's leaf message state.
 *
 * @constructor
 * @param sessionApi The API client for session-related operations.
 * @param chatApi The API client for chat message-related operations.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 *
 * @property sessionState The state of the currently loaded chat session, including all messages.
 * @property currentBranchLeafId The ID of the leaf message in the currently displayed thread branch.
 * @property displayedMessages The list of messages to display in the UI, representing the currently selected thread branch.
 * @property inputContent The current text content in the message input field.
 * @property replyTargetMessage The message the user is currently explicitly replying to via the Reply action.
 * @property editingMessage The message currently being edited (E3.S1, E3.S2).
 * @property editingContent The content of the message currently being edited (E3.S1, E3.S2).
 */
class ChatViewModel(
    private val sessionApi: SessionApi,
    private val chatApi: ChatApi,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val clock: Clock = Clock.System
) : ViewModel() {

    // --- Observable State for Compose UI (using StateFlow) ---

    private val _sessionState = MutableStateFlow<UiState<ApiError, ChatSession>>(UiState.Idle)

    /**
     * The state of the currently loaded chat session, including all messages.
     * When in Success state, provides the ChatSession object.
     */
    val sessionState: StateFlow<UiState<ApiError, ChatSession>> = _sessionState.asStateFlow()

    private val _currentBranchLeafId = MutableStateFlow<Long?>(null)

    /**
     * The ID of the leaf message in the currently displayed thread branch.
     * Changing this triggers the UI to show a different branch.
     * Null if the session is empty or not loaded/successful.
     */
    val currentBranchLeafId: StateFlow<Long?> = _currentBranchLeafId.asStateFlow()

    /**
     * The list of messages to display in the UI, representing the currently selected thread branch.
     * This is derived from the session's full list of messages and the current leaf message ID.
     */
    val displayedMessages: StateFlow<List<ChatMessage>> = combine(
        _sessionState.filterIsInstance<UiState.Success<ChatSession>>()
            .map { it.data.messages }, // Flow of just the message list when in Success state
        _currentBranchLeafId // Flow of the current leaf ID
    ) { allMessages, leafId ->
        // This is where the UI state logic (E1.S5) happens:
        // Build the thread branch whenever the message list or the leaf ID changes.
        buildThreadBranch(allMessages, leafId)
    }
        .stateIn(
            scope = CoroutineScope(viewModelScope.coroutineContext + uiDispatcher),
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )


    private val _inputContent = MutableStateFlow("")

    /**
     * The current text content in the message input field.
     */
    val inputContent: StateFlow<String> = _inputContent.asStateFlow()

    private val _replyTargetMessage = MutableStateFlow<ChatMessage?>(null)

    /**
     * The message the user is currently explicitly replying to via the Reply action (E1.S7).
     * If null, sending a message replies to the [currentBranchLeafId] value.
     */
    val replyTargetMessage: StateFlow<ChatMessage?> = _replyTargetMessage.asStateFlow()

    private val _editingMessage = MutableStateFlow<ChatMessage?>(null)

    /**
     * The message currently being edited (E3.S1, E3.S2). Null if no message is being edited.
     */
    val editingMessage: StateFlow<ChatMessage?> = _editingMessage.asStateFlow()

    private val _editingContent = MutableStateFlow("")

    /**
     * The content of the message currently being edited (E3.S1, E3.S2).
     */
    val editingContent: StateFlow<String> = _editingContent.asStateFlow()


    // --- Public Action Functions (Called by UI Components) ---

    /**
     * Loads a chat session and its messages by ID.
     * Triggered when a session is selected in the session list (E2.S4).
     *
     * @param sessionId The ID of the session to load.
     * @param forceReload If true, reloads the session even if it's already loaded successfully.
     */
    fun loadSession(sessionId: Long, forceReload: Boolean = false) {
        // Prevent reloading if already loading or if the session is already loaded successfully
        val currentState = _sessionState.value
        if (!forceReload && (currentState.isLoading || (currentState.dataOrNull?.id == sessionId))) return

        viewModelScope.launch(uiDispatcher) {
            _sessionState.value = UiState.Loading
            _replyTargetMessage.value = null
            _editingMessage.value = null
            _currentBranchLeafId.value = null

            sessionApi.getSessionDetails(sessionId)
                .fold(
                    ifLeft = { error ->
                        // Handle Error case (E1.S6)
                        _sessionState.value = UiState.Error(error)
                    },
                    ifRight = { session ->
                        // Handle Success case (E2.S4)
                        _sessionState.value = UiState.Success(session) // Success payload is the ChatSession
                        // Set initial leaf to the session's saved leaf ID or null if no messages
                        _currentBranchLeafId.value = session.currentLeafMessageId
                    }
                )
        }
    }

    /**
     * Clears the currently loaded session state.
     * Called when the selected session is deleted or potentially on app exit.
     */
    fun clearSession() {
        _sessionState.value = UiState.Idle // Go back to idle/no session state
        _replyTargetMessage.value = null
        _editingMessage.value = null
        _currentBranchLeafId.value = null
    }

    /**
     * Updates the content of the message input field.
     *
     * @param newText The new text from the input field.
     */
    fun updateInput(newText: String) {
        _inputContent.value = newText
    }

    /**
     * Sends the current message content to the active session.
     * Determines the parent based on [replyTargetMessage] or [currentBranchLeafId].
     * (E1.S1, E1.S7)
     */
    fun sendMessage() {
        val currentSession = _sessionState.value.dataOrNull ?: return // Cannot send if no session loaded successfully
        val content = _inputContent.value.trim()
        if (content.isBlank()) return // Cannot send empty message

        val parentId = _replyTargetMessage.value?.id ?: _currentBranchLeafId.value // Use value from StateFlow

        viewModelScope.launch(uiDispatcher) {
            // Optionally show a sending state/indicator (E1.S3)
            // _isSendingMessage.value = true // Requires a separate StateFlow for this granular status

            // Clear input immediately
            _inputContent.value = ""

            chatApi.processNewMessage(
                sessionId = currentSession.id,
                request = ProcessNewMessageRequest(content = content, parentMessageId = parentId)
            )
                .fold(
                    ifLeft = { error ->
                        println("Send message API error: ${error.code} - ${error.message}")
                        // Show transient error message to user (E1.S6)
                        // Requires a separate StateFlow or SharedFlow for transient UI messages
                    },
                    ifRight = { newMessages ->
                        // Handle Success case (E1.S4)
                        // We received the new user and assistant messages.
                        // Add them to the messages list in the current session state Flow.
                        val updatedMessages = currentSession.messages + newMessages
                        val newLeafId = newMessages.lastOrNull()?.id // Assume assistant message is the new leaf

                        // Update the session object inside the Success state with the new messages
                        _sessionState.value = UiState.Success(
                            currentSession.copy(
                                messages = updatedMessages,
                                currentLeafMessageId = newLeafId,
                                updatedAt = clock.now()
                            )
                        )
                        _currentBranchLeafId.value = newLeafId // Update the separate leaf state Flow

                        // Reset reply target (E1.S7)
                        _replyTargetMessage.value = null
                    }
                )
            // _isSendingMessage.value = false
        }
    }

    /**
     * Sets the state to indicate the user is replying to a specific message (E1.S7).
     *
     * @param message The message to reply to.
     */
    fun startReplyTo(message: ChatMessage) {
        _replyTargetMessage.value = message
        // Optionally, trigger scroll action in UI
    }

    /**
     * Cancels the specific reply target, reverting to replying to the current leaf (E1.S7).
     */
    fun cancelReply() {
        _replyTargetMessage.value = null
    }

    /**
     * Sets the state to indicate a message is being edited (E3.S1, E3.S2).
     *
     * @param message The message to edit.
     */
    fun startEditing(message: ChatMessage) {
        _editingMessage.value = message
        _editingContent.value = message.content
    }

    /**
     * Updates the content of the message currently being edited (E3.S1, E3.S2).
     * Called by the UI as the user types in the editing input field.
     *
     * @param newText The new text content for the editing field.
     */
    fun updateEditingContent(newText: String) {
        _editingContent.value = newText
    }

    /**
     * Saves the edited message content (E3.S3).
     */
    fun saveEditing() {
        val messageToEdit = _editingMessage.value ?: return
        val newContent = _editingContent.value.trim()
        if (newContent.isBlank()) {
            // Show inline validation error (UI concern) or update state
            println("Validation Error: Message content cannot be empty.")
            return
        }
        val currentSession = _sessionState.value.dataOrNull ?: return

        viewModelScope.launch(uiDispatcher) {
            // Optionally show inline loading/saving state for the specific message being edited
            chatApi.updateMessageContent(messageToEdit.id, UpdateMessageRequest(newContent))
                .fold(
                    ifLeft = { error ->
                        println("Edit message API error: ${error.code} - ${error.message}")
                        // Show inline error for the edited message (UI concern)
                    },
                    ifRight = { updatedMessage ->
                        // Update the message in the messages list within the current session state Flow (E3.S3)
                        val updatedAllMessages = currentSession.messages.map {
                            if (it.id == updatedMessage.id) updatedMessage else it
                        }
                        _sessionState.value = UiState.Success(
                            currentSession.copy(
                                messages = updatedAllMessages,
                                updatedAt = clock.now()
                            )
                        )
                        // displayedMessages StateFlow derived state will react

                        // Clear editing state on success
                        _editingMessage.value = null
                        _editingContent.value = ""
                    }
                )
        }
    }

    /**
     * Cancels the message editing state (E3.S1, E3.S2).
     */
    fun cancelEditing() {
        _editingMessage.value = null
        _editingContent.value = ""
    }

    /**
     * Deletes a specific message and its children from the session (E3.S4).
     *
     * @param messageId The ID of the message to delete.
     */
    fun deleteMessage(messageId: Long) {
        val currentSession = _sessionState.value.dataOrNull ?: return
        viewModelScope.launch(uiDispatcher) {
            // Optionally show inline loading state for the specific message being deleted
            chatApi.deleteMessage(messageId)
                .fold(
                    ifLeft = { error ->
                        println("Delete message API error: ${error.code} - ${error.message}")
                        // Show transient error message
                    },
                    ifRight = {
                        // Backend handled deletion recursively (E3.S4).
                        // Reload the session to update the UI state correctly (V1.1 strategy).
                        // This action launches a new coroutine within viewModelScope.
                        loadSession(currentSession.id, forceReload = true)
                    }
                )
        }
    }

    /**
     * Switches the currently displayed thread branch to the one containing the given message ID as a leaf.
     * Also persists this choice to the session record (E1.S5).
     *
     * @param messageId The ID of the message to make the new leaf of the displayed branch.
     */
    fun switchBranchToMessage(messageId: Long) {
        val currentSession = _sessionState.value.dataOrNull ?: return // Cannot switch if no session loaded successfully

        // Check if the message ID actually exists in the current messages before switching/persisting
        if (!currentSession.messages.any { it.id == messageId }) {
            println("Attempted to switch to branch with invalid message ID: $messageId")
            // Optionally show an error to the user (transient message)
            return
        }

        if (_currentBranchLeafId.value == messageId) return // Already on this branch

        viewModelScope.launch(uiDispatcher) {
            // Optimistically update UI state Flow first for responsiveness
            _currentBranchLeafId.value = messageId

            // Persist the change to the backend (E1.S5 requirement)
            sessionApi.updateSessionLeafMessage(currentSession.id, UpdateSessionLeafMessageRequest(messageId))
                .fold(
                    ifLeft = { error ->
                        println("Update leaf message API error: ${error.code} - ${error.message}")
                        // Decide rollback strategy if needed, or just show a transient error message
                    },
                    ifRight = {
                        // Persistence successful.
                        // We should also update the leafMessageId in the session object itself
                        // within the state Flow to keep the ChatSession data consistent.
                        _sessionState.value = UiState.Success(
                            currentSession.copy(currentLeafMessageId = messageId)
                        )
                        // The displayedMessages Flow reacts via _currentBranchLeafId change already handled above.
                    }
                )
        }
    }

    /**
     * Sets the selected model for the current session (E4.S7).
     *
     * @param modelId The ID of the model to select, or null to unset.
     */
    fun selectModel(modelId: Long?) {
        val currentSession = _sessionState.value.dataOrNull ?: return
        viewModelScope.launch(uiDispatcher) {
            sessionApi.updateSessionModel(currentSession.id, UpdateSessionModelRequest(modelId))
                .fold(
                    ifLeft = { error ->
                        println("Update session model API error: ${error.code} - ${error.message}")
                        // Show error
                    },
                    ifRight = {
                        // Update the session object within the state Flow manually as backend doesn't return it
                        _sessionState.value = UiState.Success(
                            currentSession.copy(currentModelId = modelId)
                        )
                    }
                )
        }
    }

    /**
     * Sets the selected settings profile for the current session (E4.S7).
     *
     * @param settingsId The ID of the settings profile to select, or null to unset.
     */
    fun selectSettings(settingsId: Long?) {
        val currentSession = _sessionState.value.dataOrNull ?: return
        viewModelScope.launch(uiDispatcher) {
            sessionApi.updateSessionSettings(currentSession.id, UpdateSessionSettingsRequest(settingsId))
                .fold(
                    ifLeft = { error ->
                        println("Update session settings API error: ${error.code} - ${error.message}")
                        // Show error
                    },
                    ifRight = {
                        // Update the session object within the state Flow manually
                        _sessionState.value = UiState.Success(
                            currentSession.copy(currentSettingsId = settingsId)
                        )
                    }
                )
        }
    }


    /**
     * Utility function to build the list of messages for a specific branch (E1.S5).
     * Operates on the flat list of messages provided.
     *
     * @param allMessages The flat list of all messages in the session.
     * @param leafId The ID of the desired leaf message for the branch.
     * @return An ordered list of messages from the root of the branch down to the leaf.
     */
    private fun buildThreadBranch(allMessages: List<ChatMessage>, leafId: Long?): List<ChatMessage> {
        if (leafId == null || allMessages.isEmpty()) return emptyList()

        val messageMap = allMessages.associateBy { it.id }
        val branch = mutableListOf<ChatMessage>()
        var currentMessageId: Long? = leafId

        // Traverse upwards from the leaf to the root
        while (currentMessageId != null) {
            val message = messageMap[currentMessageId]
            if (message == null) {
                // This indicates a data inconsistency
                println("Warning: Could not find message with ID $currentMessageId while building branch.")
                return emptyList()
            }
            branch.add(message)
            currentMessageId = message.parentMessageId
        }

        // Reverse the list to get the correct order (root to leaf)
        return branch.reversed()
    }

    // Note: Copy Message (E3.S5) and Copy Branch (E2.S7) methods are UI-only and don't involve API calls,
    // so they would remain similar, operating on the displayedMessages StateFlow value.
    // Example:
    /*
    fun copyMessageContent(message: ChatMessage) {
       // Use ClipboardManager from Compose.current.clipboardManager
       // clipboardManager.setText(AnnotatedString(message.content))
    }

    fun copyVisibleBranchContent() {
       // Format displayedMessages.value into a single string
       // Use ClipboardManager to set the text
    }
    */
}