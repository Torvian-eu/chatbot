package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.ModelRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.SettingsRepository
import eu.torvian.chatbot.app.viewmodel.chat.util.ThreadBuilder
import eu.torvian.chatbot.common.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/**
 * Reactive implementation of ChatState that derives all state from repository flows.
 *
 * This implementation follows the reactive architecture pattern where:
 * - Repositories are the single source of truth
 * - State is observed, not duplicated
 * - All UI state is derived reactively from activeSessionId and repository flows
 * - No manual state setters for derived data
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStateImpl(
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val threadBuilder: ThreadBuilder,
    private val backgroundScope: CoroutineScope
) : ChatState {

    // --- Private MutableStateFlows for Direct User Input ---

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    private val _inputContent = MutableStateFlow("")
    private val _replyTargetMessage = MutableStateFlow<ChatMessage?>(null)
    private val _editingMessage = MutableStateFlow<ChatMessage?>(null)
    private val _editingContent = MutableStateFlow("")
    private val _isSendingMessage = MutableStateFlow(false)
    private val _lastAttemptedSessionId = MutableStateFlow<Long?>(null)
    private val _lastFailedLoadEventId = MutableStateFlow<String?>(null)

    // --- Public Read-Only StateFlows ---

    override val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()
    override val inputContent: StateFlow<String> = _inputContent.asStateFlow()
    override val replyTargetMessage: StateFlow<ChatMessage?> = _replyTargetMessage.asStateFlow()
    override val editingMessage: StateFlow<ChatMessage?> = _editingMessage.asStateFlow()
    override val editingContent: StateFlow<String> = _editingContent.asStateFlow()
    override val isSendingMessage: StateFlow<Boolean> = _isSendingMessage.asStateFlow()
    override val lastAttemptedSessionId: StateFlow<Long?> = _lastAttemptedSessionId.asStateFlow()
    override val lastFailedLoadEventId: StateFlow<String?> = _lastFailedLoadEventId.asStateFlow()

    // --- Reactive State Derivation ---

    // Intermediate flow for the active session
    private val activeChatSessionState: StateFlow<DataState<RepositoryError, ChatSession>> =
        _activeSessionId.flatMapLatest { id ->
            if (id == null) flowOf(DataState.Idle)
            else sessionRepository.getSessionDetailsFlow(id)
        }.stateIn(
            scope = backgroundScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = DataState.Idle
        )

    // Intermediate flow for the active model
    private val llmModelForActiveSession: StateFlow<DataState<RepositoryError, LLMModel?>> =
        activeChatSessionState.flatMapLatest { sessionState ->
            val modelId = sessionState.dataOrNull?.currentModelId
            if (modelId == null) flowOf(DataState.Success(null))
            else modelRepository.getModelFlow(modelId)
        }.stateIn(
            scope = backgroundScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = DataState.Idle
        )

    // Intermediate flow for the active settings
    private val modelSettingsForActiveSession: StateFlow<DataState<RepositoryError, ModelSettings?>> =
        activeChatSessionState.flatMapLatest { sessionState ->
            val settingsId = sessionState.dataOrNull?.currentSettingsId
            if (settingsId == null) flowOf(DataState.Success(null))
            else settingsRepository.getSettingsFlow(settingsId)
        }.stateIn(
            scope = backgroundScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = DataState.Idle
        )

    // Final combined sessionDataState
    override val sessionDataState: StateFlow<DataState<RepositoryError, ChatSessionData>> =
        combine(
            activeChatSessionState,
            llmModelForActiveSession,
            modelSettingsForActiveSession
        ) { sessionState, modelState, settingsState ->
            when (sessionState) {
                is DataState.Idle -> DataState.Idle
                is DataState.Loading -> DataState.Loading
                is DataState.Error -> sessionState
                is DataState.Success -> {
                    val session = sessionState.data
                    val model = modelState.dataOrNull
                    val settings = settingsState.dataOrNull
                    val chatModelSettings = settings as? ChatModelSettings
                    DataState.Success(
                        ChatSessionData(
                            session = session,
                            llmModel = model,
                            modelSettings = chatModelSettings
                        )
                    )
                }
            }
        }.stateIn(
            scope = backgroundScope,
            started = SharingStarted.Eagerly,
            initialValue = DataState.Idle
        )

    // Derived displayedMessages from sessionDataState
    override val displayedMessages: StateFlow<List<ChatMessage>> =
        sessionDataState.filterIsInstance<DataState.Success<ChatSessionData>>()
            .map { it.data.session }
            .map { threadBuilder.buildThreadBranch(it.messages, it.currentLeafMessageId) }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    // --- Public State Mutation Methods ---

    override fun setActiveSessionId(sessionId: Long?) {
        _activeSessionId.value = sessionId
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

    override fun resetState() {
        _activeSessionId.value = null
        _inputContent.value = ""
        _replyTargetMessage.value = null
        _editingMessage.value = null
        _editingContent.value = ""
        _isSendingMessage.value = false
        _lastAttemptedSessionId.value = null
        _lastFailedLoadEventId.value = null
    }
}
