package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.chat.search.MessageSearchMatch
import eu.torvian.chatbot.app.chat.search.SearchDirection
import eu.torvian.chatbot.app.chat.search.findSearchMatches
import eu.torvian.chatbot.app.chat.search.navigateSearchIndex
import eu.torvian.chatbot.app.chat.search.normalizeSearchIndex
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.viewmodel.chat.util.ThreadBuilder
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

import eu.torvian.chatbot.app.chat.search.MIN_QUERY_LENGTH

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
    modelSettingsRepository: ModelSettingsRepository,
    modelRepository: ModelRepository,
    private val toolRepository: ToolRepository,
    mcpServerRepository: LocalMCPServerRepository,
    private val agentRoleRepository: AgentRoleRepository,
    private val threadBuilder: ThreadBuilder,
    backgroundScope: CoroutineScope
) : ChatState {
    companion object {
        /** Content length above which a message is collapsible. */
        private const val COLLAPSE_THRESHOLD = 500
    }

    // --- Private MutableStateFlows for Direct User Input ---

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    private val _inputContent = MutableStateFlow("")
    private val _replyTargetMessage = MutableStateFlow<ChatMessage?>(null)
    private val _editingMessage = MutableStateFlow<ChatMessage?>(null)
    private val _editingContent = MutableStateFlow("")
    private val _editingFileReferences = MutableStateFlow<List<FileReference>>(emptyList())
    private val _editingBasePathOverride = MutableStateFlow<String?>(null)
    private val _turnExecutionState = MutableStateFlow(TurnExecutionState.IDLE)
    private val _dialogState = MutableStateFlow<ChatAreaDialogState>(ChatAreaDialogState.None)
    private val _pendingFileReferences = MutableStateFlow<List<FileReference>>(emptyList())
    private val _basePathOverride = MutableStateFlow<String?>(null)
    private val _collapsedMessageIds = MutableStateFlow<Set<Long>>(emptySet())

    // --- In-Session Search State ---

    private val _isSearchActive = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<MessageSearchMatch>>(emptyList())
    private val _currentSearchIndex = MutableStateFlow(-1)
    private val _rollbackTarget = MutableStateFlow<Long?>(null)
    private val _pendingSearchMessageTarget = MutableStateFlow<Long?>(null)

    // --- Auto-collapse tracking ---
    private var lastAutoCollapsedSessionId: Long? = null

    // --- Public Read-Only StateFlows ---

    override val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()
    override val inputContent: StateFlow<String> = _inputContent.asStateFlow()
    override val replyTargetMessage: StateFlow<ChatMessage?> = _replyTargetMessage.asStateFlow()
    override val editingMessage: StateFlow<ChatMessage?> = _editingMessage.asStateFlow()
    override val editingContent: StateFlow<String> = _editingContent.asStateFlow()
    override val editingFileReferences: StateFlow<List<FileReference>> = _editingFileReferences.asStateFlow()
    override val editingBasePathOverride: StateFlow<String?> = _editingBasePathOverride.asStateFlow()
    override val turnExecutionState: StateFlow<TurnExecutionState> = _turnExecutionState.asStateFlow()
    override val dialogState: StateFlow<ChatAreaDialogState> = _dialogState.asStateFlow()
    override val pendingFileReferences: StateFlow<List<FileReference>> = _pendingFileReferences.asStateFlow()
    override val basePathOverride: StateFlow<String?> = _basePathOverride.asStateFlow()
    override val collapsedMessageIds: StateFlow<Set<Long>> = _collapsedMessageIds.asStateFlow()

    // --- In-Session Search State Flows ---

    override val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()
    override val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    override val searchResults: StateFlow<List<MessageSearchMatch>> = _searchResults.asStateFlow()
    override val currentSearchIndex: StateFlow<Int> = _currentSearchIndex.asStateFlow()
    override val rollbackTarget: StateFlow<Long?> = _rollbackTarget.asStateFlow()

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

    // All settings from repository, filtered for chat-capable settings (CHAT or RESPONSES model types)
    // only. This is the single source for chat-capable settings; it is used both to derive the
    // chat-capable model catalog and the per-model settings lists below.
    private val allSettings: StateFlow<DataState<RepositoryError, List<ModelSettings>>> =
        modelSettingsRepository.allSettings.map { dataState ->
            when (dataState) {
                is DataState.Success -> {
                    val filteredSettings = dataState.data.filter { settings ->
                        settings.modelType == LLMModelType.CHAT || settings.modelType == LLMModelType.RESPONSES
                    }
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

    // Available models from repository, filtered for active models that have at least one
    // chat-capable settings profile attached. A model carries no operational type of its own;
    // chat-capability is decided by the settings profiles that exist for it.
    override val availableModels: StateFlow<DataState<RepositoryError, List<LLMModel>>> =
        combine(modelRepository.models, allSettings) { modelsState, settingsState ->
            when (modelsState) {
                is DataState.Success -> {
                    val chatCapableModelIds = settingsState.dataOrNull.orEmpty()
                        .map { it.modelId }
                        .toSet()
                    val filteredModels = modelsState.data.filter { model ->
                        model.active && model.id in chatCapableModelIds
                    }
                    DataState.Success(filteredModels)
                }

                is DataState.Error -> modelsState
                is DataState.Loading -> modelsState
                is DataState.Idle -> modelsState
            }
        }.stateIn(
            scope = backgroundScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DataState.Idle
        )

    // Agent roles available for the chat top-bar selector: a filtered view of the repository stream
    // keeping only roles that are not disabled for the current user. Roles disabled for the user drop
    // out of `rolesById`/`currentAgentRole` immediately (reactive, no manual reload), which makes a
    // session still attached to such a role inert ("No role" + composer gated) until an enabled role
    // is selected. The settings tab reads the unfiltered repository stream instead, so disabled roles
    // stay visible and re-enableable there. Only `Success` is rewritten; the other DataState variants
    // pass through unchanged.
    override val availableAgentRoles: StateFlow<DataState<RepositoryError, List<AgentRoleDto>>> =
        agentRoleRepository.roles.map { dataState ->
            when (dataState) {
                is DataState.Success -> DataState.Success(dataState.data.filter { !it.disabled })
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

    // --- Derived Lookup Maps ---
    override val modelsById: StateFlow<Map<Long, LLMModel>> =
        allModels.map { it.dataOrNull?.associateBy { model -> model.id } ?: emptyMap() }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    override val settingsById: StateFlow<Map<Long, ModelSettings>> =
        allSettings.map { it.dataOrNull?.associateBy { settings -> settings.id } ?: emptyMap() }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Agent roles indexed by id, derived from the reactive role list for O(1) lookups when
    // resolving the session's selected role.
    private val rolesById: StateFlow<Map<Long, AgentRoleDto>> =
        availableAgentRoles.map { it.dataOrNull?.associateBy { role -> role.id } ?: emptyMap() }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // --- Derived "Current Item" States ---
    override val currentSession: StateFlow<ChatSession?> =
        sessionDataState.map { it.dataOrNull }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), null)

    // The role attached to the active session. When the session references a role that is missing
    // from the role list (deleted server-side) or no role is selected, this resolves to null and
    // the session becomes inert until a role is selected.
    override val currentAgentRole: StateFlow<AgentRoleDto?> = currentSession
        .map { session -> session?.agentRoleId }
        .distinctUntilChanged()
        .combine(rolesById) { agentRoleId, rolesMap ->
            agentRoleId?.let { rolesMap[it] }
        }.stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), null)

    // The role bundles the model and settings; a session no longer stores its own ids. Re-pointing
    // the derivations at [currentAgentRole] keeps downstream consumers (send gate, message metadata
    // rendering) working unchanged.
    override val currentModel: StateFlow<LLMModel?> = currentAgentRole
        .map { role -> role?.modelId }
        .distinctUntilChanged()
        .combine(modelsById) { modelId, modelsMap ->
            modelId?.let { modelsMap[it] }
        }.stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), null)

    override val currentSettings: StateFlow<ModelSettings?> = currentAgentRole
        .map { role -> role?.modelSettingsId }
        .distinctUntilChanged()
        .combine(settingsById) { settingsId, settingsMap ->
            settingsId?.let { settingsMap[it] }
        }.stateIn(backgroundScope, SharingStarted.WhileSubscribed(5000), null)

    // Available tools from repository, filtered for enabled tools only
    override val availableTools: StateFlow<DataState<RepositoryError, List<ToolDefinition>>> =
        toolRepository.tools.map { dataState ->
            when (dataState) {
                is DataState.Success -> {
                    val enabledTools = dataState.data.filter { it.isEnabled }
                    DataState.Success(enabledTools)
                }

                is DataState.Error -> dataState
                is DataState.Loading -> dataState
                is DataState.Idle -> dataState
            }
        }.stateIn(
            scope = backgroundScope,
            started = SharingStarted.Eagerly,
            initialValue = DataState.Idle
        )

    // Tool calls for current session - switches based on active session
    override val toolCallsForCurrentSession: StateFlow<DataState<RepositoryError, ToolCallsMap>> =
        _activeSessionId.flatMapLatest { sessionId ->
            if (sessionId == null) flowOf(DataState.Success(emptyMap()))
            else sessionRepository.getToolCallsFlow(sessionId)
        }.stateIn(
            scope = backgroundScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DataState.Success(emptyMap())
        )

    // MCP servers - directly exposed from repository
    override val mcpServers: StateFlow<DataState<RepositoryError, List<LocalMCPServerDto>>> =
        mcpServerRepository.servers

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

    init {
        // Auto-collapse messages when a new session is loaded
        combine(activeSessionId, displayedMessages) { sessionId, messages ->
            sessionId to messages
        }
            .filter { (sessionId, messages) ->
                sessionId != null && sessionId != lastAutoCollapsedSessionId && messages.isNotEmpty()
            }
            .onEach { (sessionId, _) ->
                lastAutoCollapsedSessionId = sessionId
                collapseAllDisplayedMessages()
            }
            .launchIn(backgroundScope)

        // Derive search results from displayed messages and search query. The pipeline
        // reacts to changes in either input, runs the expensive matching off the main thread
        // via mapLatest (which cancels stale work), and immediately clears results for blank
        // or too-short queries. Typing-rate limiting is handled by the UI-level debounce in
        // SearchBar, keeping the state layer straightforward.
        combine(displayedMessages, searchQuery) { messages, query ->
            messages to query
        }
            .mapLatest { (messages, query) ->
                // Blank or too-short queries clear immediately without background work.
                if (query.isBlank() || query.length < MIN_QUERY_LENGTH) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) {
                        findSearchMatches(messages, query)
                    }
                }
            }
            .combine(_pendingSearchMessageTarget) { results, pendingTarget ->
                results to pendingTarget
            }
            .onEach { (results, pendingTarget) ->
                _searchResults.value = results
                // Normalize current index when results change
                _currentSearchIndex.value = normalizeSearchIndex(results, _currentSearchIndex.value)
                // Consume pending target: if set, find and select the target message
                pendingTarget?.let { targetMessageId ->
                    val targetIndex = results.indexOfFirst { it.messageId == targetMessageId }
                    if (targetIndex >= 0) {
                        _currentSearchIndex.value = targetIndex
                    }
                    // Clear the pending target after consumption
                    _pendingSearchMessageTarget.value = null
                }
            }
            .launchIn(backgroundScope)
    }

    // --- Public State Mutation Methods ---

    override fun setActiveSessionId(sessionId: Long?) {
        _activeSessionId.value = sessionId
    }

    override fun setInputContent(content: String) {
        _inputContent.value = content
    }

    override fun toggleMessageCollapsed(messageId: Long) {
        _collapsedMessageIds.update { current ->
            if (messageId in current) current - messageId else current + messageId
        }
    }

    override fun collapseAllDisplayedMessages() {
        _collapsedMessageIds.update { current ->
            displayedMessages.value.filter { it.content.length > COLLAPSE_THRESHOLD }.map { it.id }.toSet() + current
        }
    }

    override fun expandAllDisplayedMessages() {
        _collapsedMessageIds.update { current ->
            current - displayedMessages.value.map { it.id }.toSet()
        }
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

    override fun setEditingFileReferences(fileReferences: List<FileReference>) {
        _editingFileReferences.value = fileReferences
    }

    override fun updateEditingFileReferences(transform: (List<FileReference>) -> List<FileReference>) {
        _editingFileReferences.value = transform(_editingFileReferences.value)
    }

    override fun setEditingBasePathOverride(path: String?) {
        _editingBasePathOverride.value = path
    }

    override fun setTurnExecutionState(executionState: TurnExecutionState) {
        _turnExecutionState.value = executionState
    }

    override fun setDialogState(dialogState: ChatAreaDialogState) {
        _dialogState.value = dialogState
    }

    override fun cancelDialog() {
        _dialogState.value = ChatAreaDialogState.None
    }

    override fun updateFileReferences(transform: (List<FileReference>) -> List<FileReference>) {
        _pendingFileReferences.value = transform(_pendingFileReferences.value)
    }


    override fun setBasePathOverride(path: String?) {
        _basePathOverride.value = path
    }

    override fun resetState() {
        _activeSessionId.value = null
        _inputContent.value = ""
        _replyTargetMessage.value = null
        _editingMessage.value = null
        _editingContent.value = ""
        _editingFileReferences.value = emptyList()
        _editingBasePathOverride.value = null
        _turnExecutionState.value = TurnExecutionState.IDLE
        _dialogState.value = ChatAreaDialogState.None
        _pendingFileReferences.value = emptyList()
        _basePathOverride.value = null
        _collapsedMessageIds.value = emptySet()
        lastAutoCollapsedSessionId = null
        // Reset search state
        _isSearchActive.value = false
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _currentSearchIndex.value = -1
        _rollbackTarget.value = null
        _pendingSearchMessageTarget.value = null
    }

    // --- In-Session Search State Mutation Methods ---

    override fun showSearch() {
        _isSearchActive.value = true
    }

    override fun closeSearch() {
        // Preserve rollback state, only clear search UI state
        _isSearchActive.value = false
        _searchQuery.value = ""
        _currentSearchIndex.value = -1
        _pendingSearchMessageTarget.value = null
    }

    override fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        // Reset index - will be normalized in the combine flow
        _currentSearchIndex.value = -1
    }

    override fun navigateSearchResult(direction: SearchDirection) {
        val newIndex = navigateSearchIndex(
            searchResults = _searchResults.value,
            currentIndex = _currentSearchIndex.value,
            direction = direction,
        )
        _currentSearchIndex.value = newIndex
    }

    override fun jumpToSearchResult(index: Int) {
        _currentSearchIndex.value = normalizeSearchIndex(_searchResults.value, index)
    }

    override fun setRollbackTarget(targetMessageId: Long?) {
        _rollbackTarget.value = targetMessageId
    }

    override fun setPendingSearchMessageTarget(messageId: Long?) {
        _pendingSearchMessageTarget.value = messageId
    }
}
