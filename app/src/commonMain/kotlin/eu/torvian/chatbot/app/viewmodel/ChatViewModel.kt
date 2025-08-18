package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.app.domain.events.SnackbarInteractionEvent
import eu.torvian.chatbot.app.domain.events.apiRequestError
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_loading_session
import eu.torvian.chatbot.app.generated.resources.error_sending_message_short
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.jetbrains.compose.resources.getString

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
 * @param settingsApi The API client for model settings.
 * @param eventBus The event bus for emitting global events like retry-able errors.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 * @param clock The clock to use for timestamping. Defaults to System clock.
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
    private val settingsApi: SettingsApi,
    private val eventBus: EventBus,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val clock: Clock = Clock.System
) : ViewModel() {

    private val logger = kmpLogger<ChatViewModel>()

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

    // New state to hold the actively streaming assistant message
    private val _streamingAssistantMessage = MutableStateFlow<ChatMessage.AssistantMessage?>(null)

    // New state to hold the temporary user message during streaming
    private val _streamingUserMessage = MutableStateFlow<ChatMessage.UserMessage?>(null)

    /**
     * The list of messages to display in the UI, representing the currently selected thread branch.
     * This is derived from the session's full list of messages and the current leaf message ID,
     * combined with any actively streaming message.
     */
    val displayedMessages: StateFlow<List<ChatMessage>> = combine(
        _sessionState.filterIsInstance<UiState.Success<ChatSession>>()
            .map { it.data.messages }, // Flow of just the message list when in Success state
        _currentBranchLeafId, // Flow of the current leaf ID
        _streamingAssistantMessage // Include the streaming message flow
    ) { allPersistedMessages, leafId, streamingAssistantMessage ->
        // This is where the UI state logic (E1.S5) happens:
        // Prepare the list of messages for `buildThreadBranch`.
        val messagesForBranching = if (streamingAssistantMessage != null) {
            // If streaming, create a copy of the persisted messages
            // and replace/add the streaming message for real-time display.
            // This ensures `buildThreadBranch` always sees the latest state.
            val updatedMessages = allPersistedMessages.toMutableList()
            val existingIndex = updatedMessages.indexOfFirst { it.id == streamingAssistantMessage.id }
            if (existingIndex != -1) {
                updatedMessages[existingIndex] = streamingAssistantMessage
            } else {
                updatedMessages.add(streamingAssistantMessage)
            }
            updatedMessages.toList()
        } else {
            allPersistedMessages
        }
        buildThreadBranch(messagesForBranching, leafId)
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

    private val _isSendingMessage = MutableStateFlow(false)

    /**
     * Indicates whether a message is currently in the process of being sent. (E1.S3)
     */
    val isSendingMessage: StateFlow<Boolean> = _isSendingMessage.asStateFlow()

    // Store the ID of the last emitted error if a retry is possible
    private val _lastFailedLoadEventId = MutableStateFlow<String?>(null)

    // Store the session ID for retry functionality
    private val _lastAttemptedSessionId = MutableStateFlow<Long?>(null)

    init {
        // ViewModel can listen to the EventBus for its own emitted event's responses
        viewModelScope.launch(uiDispatcher) {
            eventBus.events.collect { event ->
                if (event is SnackbarInteractionEvent && event.originalAppEventId == _lastFailedLoadEventId.value) {
                    if (event.isActionPerformed) {
                        logger.info("Retrying loadSession due to Snackbar action!")
                        _lastFailedLoadEventId.value = null // Clear ID before retrying
                        _lastAttemptedSessionId.value?.let { sessionId ->
                            loadSession(sessionId, forceReload = true) // Trigger retry
                        }
                    } else { // It was dismissed (by user or timeout)
                        logger.info("Snackbar dismissed, not retrying loadSession.")
                        _lastFailedLoadEventId.value = null
                        _lastAttemptedSessionId.value = null
                    }
                }
            }
        }
    }

    // --- Public Action Functions (Called by UI Components) ---

    /**
     * Loads a chat session and its messages by ID.
     * Triggered when a session is selected in the session list (E2.S4).
     *
     * @param sessionId The ID of the session to load, or null to clear the session.
     * @param forceReload If true, reloads the session even if it's already loaded successfully.
     */
    fun loadSession(sessionId: Long?, forceReload: Boolean = false) {
        // Prevent reloading if already loading or if the session is already loaded successfully
        val currentState = _sessionState.value
        if (!forceReload && (currentState.isLoading || (currentState.dataOrNull?.id == sessionId))) return

        if (sessionId == null) {
            clearSession()
            return
        }

        // Store the session ID for potential retry
        _lastAttemptedSessionId.value = sessionId

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
                        // Emit to generic EventBus using the specific error type
                        val globalError = apiRequestError(
                            apiError = error,
                            shortMessage = getString(Res.string.error_loading_session),
                            isRetryable = true
                        )
                        _lastFailedLoadEventId.value = globalError.eventId // Store its ID
                        eventBus.emitEvent(globalError)
                    },
                    ifRight = { session ->
                        // Handle Success case (E2.S4)
                        _sessionState.value = UiState.Success(session) // Success payload is the ChatSession
                        // Set initial leaf to the session's saved leaf ID or null if no messages
                        _currentBranchLeafId.value = session.currentLeafMessageId
                        // Clear retry state on success
                        _lastFailedLoadEventId.value = null
                        _lastAttemptedSessionId.value = null
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
        _streamingAssistantMessage.value = null
        _streamingUserMessage.value = null
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
            _isSendingMessage.value = true // Set sending state to true (E1.S3)

            try {
                // Check if streaming is enabled in settings
                val isStreamingEnabled = true // TODO: Implement settings check

                if (isStreamingEnabled) {
                    // Handle streaming message
                    handleStreamingMessage(currentSession, content, parentId)
                } else {
                    // Handle non-streaming message (existing logic)
                    chatApi.processNewMessage(
                        sessionId = currentSession.id,
                        request = ProcessNewMessageRequest(content = content, parentMessageId = parentId)
                    )
                        .fold(
                            ifLeft = { error ->
                                logger.error("Send message API error: ${error.code} - ${error.message}")
                                // Emit to EventBus for Snackbar display (E1.S6)
                                eventBus.emitEvent(
                                    apiRequestError(
                                        apiError = error,
                                        shortMessage = getString(Res.string.error_sending_message_short),
                                    )
                                )
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
                                _inputContent.value = "" // Clear input field
                            }
                        )
                }
            } finally {
                _isSendingMessage.value = false // Always reset sending state
            }
        }
    }

    /**
     * Handles streaming message processing.
     */
    private suspend fun handleStreamingMessage(currentSession: ChatSession, content: String, parentId: Long?) {
        // Clear any previous streaming state
        _streamingUserMessage.value = null
        _streamingAssistantMessage.value = null

        chatApi.processNewMessageStreaming(
            sessionId = currentSession.id,
            request = ProcessNewMessageRequest(content = content, parentMessageId = parentId)
        ).collect { eitherUpdate ->
            eitherUpdate.fold(
                ifLeft = { error ->
                    logger.error("Streaming message API error: ${error.code} - ${error.message}")
                    // Clear any streaming state and emit error
                    _streamingAssistantMessage.value = null
                    _streamingUserMessage.value = null
                    eventBus.emitEvent(
                        apiRequestError(
                            apiError = error,
                            shortMessage = getString(Res.string.error_sending_message_short),
                        )
                    )
                },
                ifRight = { chatUpdate ->
                    when (chatUpdate) {
                        is ChatStreamEvent.UserMessageSaved -> {
                            // Store the user message in the temporary streaming state
                            _streamingUserMessage.value = chatUpdate.message
                            // Add the user message to the session immediately
                            val updatedMessages = currentSession.messages + chatUpdate.message
                            _sessionState.value = UiState.Success(
                                currentSession.copy(
                                    messages = updatedMessages,
                                    updatedAt = clock.now()
                                )
                            )
                            _currentBranchLeafId.value = chatUpdate.message.id
                            // Clear input and reply target after user message is confirmed
                            _inputContent.value = ""
                            _replyTargetMessage.value = null
                        }

                        is ChatStreamEvent.AssistantMessageStart -> {// Use the assistant message directly from the update
                            _currentBranchLeafId.value = chatUpdate.assistantMessage.id
                            _streamingAssistantMessage.value = chatUpdate.assistantMessage
                        }

                        is ChatStreamEvent.AssistantMessageDelta -> {// Update the streaming message content
                            _streamingAssistantMessage.value?.let { currentStreamingMessage ->
                                _streamingAssistantMessage.value = currentStreamingMessage.copy(
                                    content = currentStreamingMessage.content + chatUpdate.deltaContent
                                )
                            }
                        }

                        is ChatStreamEvent.AssistantMessageEnd -> {
                            // Replace the temporary streaming message with the final persisted messages
                            val finalAssistantMessage = chatUpdate.finalAssistantMessage
                            val finalUserMessage = chatUpdate.finalUserMessage

                            // Update the session with both the updated user message and the final assistant message
                            // Remove the old user message and add both updated messages
                            val updatedMessages =
                                currentSession.messages.filterNot { it.id == _streamingUserMessage.value?.id } +
                                        finalUserMessage + finalAssistantMessage
                            val newLeafId = finalAssistantMessage.id

                            _sessionState.value = UiState.Success(
                                currentSession.copy(
                                    messages = updatedMessages,
                                    currentLeafMessageId = newLeafId,
                                    updatedAt = clock.now()
                                )
                            )
                            _currentBranchLeafId.value = newLeafId
                            _streamingAssistantMessage.value = null // Clear streaming state
                            _streamingUserMessage.value = null // Clear streaming user message
                        }

                        is ChatStreamEvent.ErrorOccurred -> {
                            // Handle error during streaming
                            logger.error("Streaming error: ${chatUpdate.error.message}")
                            _streamingAssistantMessage.value = null
                            _streamingUserMessage.value = null
                            eventBus.emitEvent(
                                apiRequestError(
                                    apiError = chatUpdate.error,
                                    shortMessage = getString(Res.string.error_sending_message_short),
                                )
                            )
                        }

                        ChatStreamEvent.StreamCompleted -> {
                            // Streaming completed successfully
                            logger.info("Streaming completed for session ${currentSession.id}")
                        }
                    }
                }
            )
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
     * Switches the currently displayed chat branch to the one that includes the given message ID.
     * The ViewModel will find the actual leaf message of this branch by traversing down
     * the path of first children starting from the provided `targetMessageId`.
     * This new leaf message ID is then persisted to the session record (E1.S5).
     *
     * @param targetMessageId The ID of the message that serves as the starting point for
     *                        determining the new displayed branch. This message itself may be
     *                        a root, middle, or leaf message in the conversation tree.
     */
    fun switchBranchToMessage(targetMessageId: Long) {
        val currentSession = _sessionState.value.dataOrNull ?: return
        if (_currentBranchLeafId.value == targetMessageId) return

        val messageMap = currentSession.messages.associateBy { it.id }

        // Use the new helper function to find the actual leaf ID
        val finalLeafId = findLeafOfBranch(targetMessageId, messageMap)
        if (finalLeafId == null) {
            println("Warning: Could not determine a valid leaf for branch starting with $targetMessageId.")
            return
        }

        if (_currentBranchLeafId.value == finalLeafId) return // Already on this exact branch

        viewModelScope.launch(uiDispatcher) {
            // Optimistically update UI state Flow first for responsiveness
            _currentBranchLeafId.value = finalLeafId

            // Persist the change to the backend (E1.S5 requirement)
            sessionApi.updateSessionLeafMessage(currentSession.id, UpdateSessionLeafMessageRequest(finalLeafId))
                .fold(
                    ifLeft = { error ->
                        println("Update leaf message API error: ${error.code} - ${error.message}")
                        // Decide rollback strategy if needed, or just show a transient error message.
                    },
                    ifRight = {
                        // Persistence successful.
                        // We should also update the leafMessageId in the session object itself
                        // within the state Flow to keep the ChatSession data consistent.
                        _sessionState.value = UiState.Success(
                            currentSession.copy(currentLeafMessageId = finalLeafId)
                        )
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
     * Finds the ultimate leaf message ID by traversing down the
     * first child path from a given starting message ID.
     *
     * @param startMessageId The ID of the message to start the traversal from.
     * @param messageMap A map of all messages in the session for efficient lookup.
     * @return The ID of the leaf message found, or null if the startMessageId is invalid or a cycle/broken link is detected.
     */
    private fun findLeafOfBranch(startMessageId: Long, messageMap: Map<Long, ChatMessage>): Long? {
        var currentPathMessage: ChatMessage? = messageMap[startMessageId]
        if (currentPathMessage == null) {
            println("Warning: Starting message for branch traversal not found: $startMessageId")
            return null
        }

        var finalLeafId: Long = startMessageId
        val visitedIds = mutableSetOf<Long>() // To detect cycles and prevent infinite loops

        while (currentPathMessage?.childrenMessageIds?.isNotEmpty() == true) {
            if (!visitedIds.add(currentPathMessage.id)) {
                // Cycle detected
                println("Warning: Cycle detected in message thread path at message ID: ${currentPathMessage.id}. Aborting traversal.")
                break
            }
            // Select the first child to traverse down
            val firstChildId = currentPathMessage.childrenMessageIds.first()
            currentPathMessage = messageMap[firstChildId]
            if (currentPathMessage == null) {
                // Data inconsistency: a child ID exists but the message is not in the map
                println("Warning: Child message $firstChildId not found during branch traversal. Using last valid message as leaf.")
                break
            }
            finalLeafId = currentPathMessage.id
        }
        return finalLeafId
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
        val visitedIds = mutableSetOf<Long>() // Added for cycle detection

        // Traverse upwards from the leaf to the root
        while (currentMessageId != null) {
            val message = messageMap[currentMessageId]
            if (message == null) {
                // This indicates a data inconsistency
                println("Warning: Could not find message with ID $currentMessageId while building branch. Aborting traversal.")
                return emptyList() // Return empty as the branch is incomplete/corrupt
            }
            if (!visitedIds.add(message.id)) {
                // Cycle detected during upward traversal
                println("Warning: Cycle detected in message thread path during upward traversal at message ID: ${message.id}. Aborting traversal.")
                return emptyList() // Return empty as the branch is corrupted
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