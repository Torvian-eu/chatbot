package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.app.viewmodel.chat.util.ThreadBuilder
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock

/**
 * Shared state holder for chat-related UI state.
 * Owns all MutableStateFlows and provides read-only StateFlows to consumers.
 * Encapsulates state mutations through the ChatState interface.
 */
class ChatStateImpl(
    private val threadBuilder: ThreadBuilder,
    private val clock: Clock,
    backgroundScope: CoroutineScope
) : ChatState {

    // --- Private MutableStateFlows ---

    private val _sessionState = MutableStateFlow<UiState<ApiError, ChatSession>>(UiState.Idle)
    private val _currentBranchLeafId = MutableStateFlow<Long?>(null)
    private val _streamingAssistantMessage = MutableStateFlow<ChatMessage.AssistantMessage?>(null)
    private val _streamingUserMessage = MutableStateFlow<ChatMessage.UserMessage?>(null)
    private val _inputContent = MutableStateFlow("")
    private val _replyTargetMessage = MutableStateFlow<ChatMessage?>(null)
    private val _editingMessage = MutableStateFlow<ChatMessage?>(null)
    private val _editingContent = MutableStateFlow("")
    private val _isSendingMessage = MutableStateFlow(false)
    private val _lastAttemptedSessionId = MutableStateFlow<Long?>(null)
    private val _lastFailedLoadEventId = MutableStateFlow<String?>(null)

    // --- Public Read-Only StateFlows ---

    override val sessionState: StateFlow<UiState<ApiError, ChatSession>> = _sessionState.asStateFlow()
    override val currentBranchLeafId: StateFlow<Long?> = _currentBranchLeafId.asStateFlow()
    override val inputContent: StateFlow<String> = _inputContent.asStateFlow()
    override val replyTargetMessage: StateFlow<ChatMessage?> = _replyTargetMessage.asStateFlow()
    override val editingMessage: StateFlow<ChatMessage?> = _editingMessage.asStateFlow()
    override val editingContent: StateFlow<String> = _editingContent.asStateFlow()
    override val isSendingMessage: StateFlow<Boolean> = _isSendingMessage.asStateFlow()
    override val lastAttemptedSessionId: StateFlow<Long?> = _lastAttemptedSessionId.asStateFlow()
    override val lastFailedLoadEventId: StateFlow<String?> = _lastFailedLoadEventId.asStateFlow()

    // --- Computed StateFlows ---

    override val displayedMessages: StateFlow<List<ChatMessage>> = combine(
        _sessionState.filterIsInstance<UiState.Success<ChatSession>>()
            .map { it.data.messages },
        _currentBranchLeafId,
        _streamingUserMessage,
        _streamingAssistantMessage
    ) { allPersistedMessages, leafId, streamingUserMessage, streamingAssistantMessage ->
        val messagesForBranching = allPersistedMessages + listOfNotNull(streamingUserMessage, streamingAssistantMessage)
        threadBuilder.buildThreadBranch(messagesForBranching, leafId)
    }.stateIn(
        scope = backgroundScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    // --- Public State Mutation Methods ---

    override fun setSessionLoading() {
        _sessionState.value = UiState.Loading
    }

    override fun setSessionError(error: ApiError) {
        _sessionState.value = UiState.Error(error)
    }

    override fun setSessionSuccess(session: ChatSession) {
        _sessionState.value = UiState.Success(session)
    }

    override fun setCurrentLeafId(leafId: Long?) {
        _currentBranchLeafId.value = leafId
    }

    override fun setStreamingUserMessage(message: ChatMessage.UserMessage?) {
        _streamingUserMessage.value = message
    }

    override fun setStreamingAssistantMessage(message: ChatMessage.AssistantMessage?) {
        _streamingAssistantMessage.value = message
    }

    override fun setInputContent(content: String) {
        _inputContent.value = content
    }

    override fun setReplyTarget(message: ChatMessage?) {
        _replyTargetMessage.value = message
    }

    override fun setEditingMessage(message: ChatMessage?) {
        _editingMessage.value = message
    }

    override fun setEditingContent(content: String) {
        _editingContent.value = content
    }

    override fun setIsSending(isSending: Boolean) {
        _isSendingMessage.value = isSending
    }

    override fun setRetryState(sessionId: Long?, eventId: String?) {
        _lastAttemptedSessionId.value = sessionId
        _lastFailedLoadEventId.value = eventId
    }

    override fun clearRetryState() {
        _lastAttemptedSessionId.value = null
        _lastFailedLoadEventId.value = null
    }

    override fun updateSessionMessages(messages: List<ChatMessage>, newLeafId: Long?) {
        val currentSession = _sessionState.value.dataOrNull ?: return
        _sessionState.value = UiState.Success(
            currentSession.copy(
                messages = messages,
                currentLeafMessageId = newLeafId,
                updatedAt = clock.now()
            )
        )
        _currentBranchLeafId.value = newLeafId
    }

    override fun updateSessionModelId(modelId: Long?) {
        val currentSession = _sessionState.value.dataOrNull ?: return
        _sessionState.value = UiState.Success(
            currentSession.copy(currentModelId = modelId)
        )
    }

    override fun updateSessionSettingsId(settingsId: Long?) {
        val currentSession = _sessionState.value.dataOrNull ?: return
        _sessionState.value = UiState.Success(
            currentSession.copy(currentSettingsId = settingsId)
        )
    }

    override fun resetState() {
        _sessionState.value = UiState.Idle
        _currentBranchLeafId.value = null
        _streamingAssistantMessage.value = null
        _streamingUserMessage.value = null
        _inputContent.value = ""
        _replyTargetMessage.value = null
        _editingMessage.value = null
        _editingContent.value = ""
        _isSendingMessage.value = false
        _lastAttemptedSessionId.value = null
        _lastFailedLoadEventId.value = null
    }
}
