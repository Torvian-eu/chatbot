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
    private val _dialogState = MutableStateFlow<ChatAreaDialogState>(ChatAreaDialogState.None)
    private val _lastAttemptedSessionId = MutableStateFlow<Long?>(null)
    private val _lastFailedLoadEventId = MutableStateFlow<String?>(null)

    // --- Public Read-Only StateFlows ---

    override val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()
    override val inputContent: StateFlow<String> = _inputContent.asStateFlow()
    override val replyTargetMessage: StateFlow<ChatMessage?> = _replyTargetMessage.asStateFlow()
    override val editingMessage: StateFlow<ChatMessage?> = _editingMessage.asStateFlow()
    override val editingContent: StateFlow<String> = _editingContent.asStateFlow()
    override val isSendingMessage: StateFlow<Boolean> = _isSendingMessage.asStateFlow()
    override val dialogState: StateFlow<ChatAreaDialogState> = _dialogState.asStateFlow()
    override val lastAttemptedSessionId: StateFlow<Long?> = _lastAttemptedSessionId.asStateFlow()
    override val lastFailedLoadEventId: StateFlow<String?> = _lastFailedLoadEventId.asStateFlow()

    // --- Reactive State Derivation ---

    // Core session state
    override val sessionDataState: StateFlow<DataState<RepositoryError, ChatSession>> =
        _activeSessionId.flatMapLatest { id ->
            if (id == null) flowOf(DataState.Idle)
            else sessionRepository.getSessionDetailsFlow(id)
        }.stateIn(
            scope = backgroundScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DataState.Idle
        )

    // Available models from repository, filtered for chat models and active models only
    override val availableModels: StateFlow<DataState<RepositoryError, List<LLMModel>>> =
        modelRepository.models.map { dataState ->
            when (dataState) {
                is DataState.Success -> {
                    val filteredModels = dataState.data.filter { model ->
                        model.type == LLMModelType.CHAT && model.active
                    }
                    DataState.Success(filteredModels)
                }

                is DataState.Error -> dataState
                is DataState.Loading -> dataState
                is DataState.Idle -> dataState
            }
        }.stateIn(
            scope = backgroundScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DataState.Idle
        )

    private val allModels: StateFlow<DataState<RepositoryError, List<LLMModel>>> = modelRepository.models

    // All settings from repository, filtered for chat model settings only
    private val allSettings: StateFlow<DataState<RepositoryError, List<ChatModelSettings>>> =
        settingsRepository.settings.map { dataState ->
            when (dataState) {
                is DataState.Success -> {
                    val filteredSettings = dataState.data.filterIsInstance<ChatModelSettings>()
                    DataState.Success(filteredSettings)
                }

                is DataState.Error -> dataState
                is DataState.Loading -> dataState
                is DataState.Idle -> dataState
            }
        }.stateIn(
            scope = backgroundScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DataState.Idle
        )

    // --- Derived Lookup Maps ---
    override val modelsById: StateFlow<Map<Long, LLMModel>> =
        allModels.map { it.dataOrNull?.associateBy { model -> model.id } ?: emptyMap() }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    override val settingsById: StateFlow<Map<Long, ChatModelSettings>> =
        allSettings.map { it.dataOrNull?.associateBy { settings -> settings.id } ?: emptyMap() }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // --- Derived "Current Item" States ---
    override val currentSession: StateFlow<ChatSession?> =
        sessionDataState.map { it.dataOrNull }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), null)

    override val currentModel: StateFlow<LLMModel?> = currentSession
        .map { session -> session?.currentModelId }
        .distinctUntilChanged()
        .combine(modelsById) { currentModelId, modelsMap ->
            currentModelId?.let { modelsMap[it] }
        }.stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), null)

    override val currentSettings: StateFlow<ChatModelSettings?> = currentSession
        .map { session -> session?.currentSettingsId }
        .distinctUntilChanged()
        .combine(settingsById) { currentSettingsId, settingsMap ->
            currentSettingsId?.let { settingsMap[it] }
        }.stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), null)

    // --- Derived Filtered List for UI ---
    override val availableSettingsForCurrentModel: StateFlow<DataState<RepositoryError, List<ChatModelSettings>>> =
        combine(currentModel, allSettings) { model, settingsState ->
            val currentModelId = model?.id
            when (settingsState) {
                is DataState.Success -> {
                    val filtered = if (currentModelId != null) {
                        settingsState.data.filter { it.modelId == currentModelId }
                    } else {
                        emptyList()
                    }
                    DataState.Success(filtered)
                }
                // Pass through Error, Loading, Idle states directly
                is DataState.Error -> settingsState
                is DataState.Loading -> DataState.Loading
                is DataState.Idle -> DataState.Idle
            }
        }.stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), DataState.Idle)

    // Derived displayedMessages from sessionDataState
    override val displayedMessages: StateFlow<List<ChatMessage>> =
        sessionDataState.filterIsInstance<DataState.Success<ChatSession>>()
            .map { it.data }
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

    override fun setDialogState(dialogState: ChatAreaDialogState) {
        _dialogState.value = dialogState
    }

    override fun cancelDialog() {
        _dialogState.value = ChatAreaDialogState.None
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
        _dialogState.value = ChatAreaDialogState.None
        clearRetryState()
    }
}
