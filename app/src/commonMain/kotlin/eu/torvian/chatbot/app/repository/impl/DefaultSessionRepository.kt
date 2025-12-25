package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.ToolCallsMap
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.app.utils.misc.LruCache
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.core.*
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.tool.ToolCall
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * Default implementation of [SessionRepository] that manages chat sessions.
 *
 * This repository maintains an internal cache of session data using [MutableStateFlow] and
 * provides reactive updates to all observers. It delegates API operations to the injected
 * [SessionApi] and [ChatApi] and handles comprehensive error management through [RepositoryError].
 *
 * The repository ensures data consistency by automatically updating the internal StateFlow
 * whenever successful CRUD operations occur, eliminating the need for manual cache invalidation.
 *
 * @property sessionApi The API client for session-related operations
 * @property chatApi The API client for chat message operations
 */
class DefaultSessionRepository(
    private val sessionApi: SessionApi,
    private val chatApi: ChatApi
) : SessionRepository {

    companion object {
        private val logger = kmpLogger<DefaultSessionRepository>()
        private const val SESSION_DETAILS_CACHE_SIZE = 10
        private const val TOOL_CALLS_CACHE_SIZE = 10
    }

    private val _sessions = MutableStateFlow<DataState<RepositoryError, List<ChatSessionSummary>>>(DataState.Idle)
    override val sessions: StateFlow<DataState<RepositoryError, List<ChatSessionSummary>>> = _sessions.asStateFlow()

    // Cache for individual detailed session flows, guarded by a Mutex for thread safety
    private val _sessionDetailsFlowsMutex = Mutex()
    private val _sessionDetailsFlows =
        LruCache<Long, MutableStateFlow<DataState<RepositoryError, ChatSession>>>(SESSION_DETAILS_CACHE_SIZE)

    // Cache for individual session tool call flows, guarded by a Mutex for thread safety
    private val _toolCallsFlowsMutex = Mutex()
    private val _toolCallsFlows =
        LruCache<Long, MutableStateFlow<DataState<RepositoryError, ToolCallsMap>>>(TOOL_CALLS_CACHE_SIZE)

    override suspend fun getSessionDetailsFlow(sessionId: Long): StateFlow<DataState<RepositoryError, ChatSession>> {
        return _sessionDetailsFlowsMutex.withLock {
            _sessionDetailsFlows.getOrPut(sessionId) {
                // If not found, create a new flow initialized to Idle
                MutableStateFlow(DataState.Idle)
            }.asStateFlow()
        }
    }

    override suspend fun getToolCallsFlow(sessionId: Long): StateFlow<DataState<RepositoryError, ToolCallsMap>> {
        return _toolCallsFlowsMutex.withLock {
            _toolCallsFlows.getOrPut(sessionId) {
                // If not found, create a new flow initialized to Success with an empty map
                MutableStateFlow(DataState.Success(emptyMap()))
            }.asStateFlow()
        }
    }

    override suspend fun loadSessions(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_sessions.value.isLoading) return Unit.right()
        _sessions.update { DataState.Loading }
        return sessionApi.getAllSessions()
            .map { sessionList ->
                _sessions.update { DataState.Success(sessionList) }
            }
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load sessions")
                _sessions.update { DataState.Error(repositoryError) }
                repositoryError
            }
    }

    override suspend fun createSession(request: CreateSessionRequest): Either<RepositoryError, ChatSession> {
        return sessionApi.createSession(request)
            .map { newSession ->
                // Add the new session to the internal list of summaries
                _sessions.update { currentState ->
                    when (currentState) {
                        is DataState.Success -> {
                            val newSummary = ChatSessionSummary(
                                id = newSession.id,
                                name = newSession.name,
                                groupId = newSession.groupId,
                                createdAt = newSession.createdAt,
                                updatedAt = newSession.updatedAt
                            )
                            val updatedSessions = currentState.data + newSummary
                            DataState.Success(updatedSessions)
                        }

                        else -> currentState // Keep other states (Loading, Error) unchanged
                    }
                }

                // Get or create the flow for this new session and set its state to Success
                _sessionDetailsFlowsMutex.withLock {
                    val flow = _sessionDetailsFlows.getOrPut(newSession.id) {
                        MutableStateFlow(DataState.Idle)
                    }
                    flow.value = DataState.Success(newSession)
                }
                newSession
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to create session")
            }
    }

    override suspend fun loadSessionDetails(sessionId: Long): Either<RepositoryError, ChatSession> {
        val sessionFlow = _sessionDetailsFlowsMutex.withLock {
            _sessionDetailsFlows.getOrPut(sessionId) {
                MutableStateFlow(DataState.Idle)
            }
        }
        sessionFlow.update { DataState.Loading }
        return sessionApi.getSessionDetails(sessionId)
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load session details")
                sessionFlow.update { DataState.Error(repositoryError) }
                repositoryError
            }
            .onRight { sessionDetails ->
                sessionFlow.update { DataState.Success(sessionDetails) }
            }

    }

    override suspend fun loadSessionToolCalls(sessionId: Long): Either<RepositoryError, ToolCallsMap> {
        val toolCallsFlow = _toolCallsFlowsMutex.withLock {
            _toolCallsFlows.getOrPut(sessionId) {
                MutableStateFlow(DataState.Idle)
            }
        }
        toolCallsFlow.update { DataState.Loading }
        return sessionApi.getSessionToolCalls(sessionId)
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load tool calls")
                toolCallsFlow.update { DataState.Error(repositoryError) }
                repositoryError
            }
            .map { toolCalls ->
                val toolCallsMap = toolCalls.groupBy { it.messageId }
                toolCallsFlow.update { DataState.Success(toolCallsMap) }
                toolCallsMap
            }
    }

    override suspend fun deleteSession(sessionId: Long): Either<RepositoryError, Unit> {
        return sessionApi.deleteSession(sessionId)
            .map {
                // Remove the session from the internal list of summaries
                _sessions.update { currentState ->
                    when (currentState) {
                        is DataState.Success -> {
                            val filteredSessions = currentState.data.filter { it.id != sessionId }
                            DataState.Success(filteredSessions)
                        }

                        else -> currentState // Keep other states unchanged
                    }
                }

                // Remove the session's details flow from the cache, atomically
                _sessionDetailsFlowsMutex.withLock<Unit> {
                    _sessionDetailsFlows.remove(sessionId)
                }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to delete session")
            }
    }

    override suspend fun updateSessionName(
        sessionId: Long,
        request: UpdateSessionNameRequest
    ): Either<RepositoryError, Unit> {
        return sessionApi.updateSessionName(sessionId, request)
            .map {
                // Update summary list
                updateSessionInList(sessionId) { session ->
                    session.copy(name = request.name)
                }
                // Update details cache if present.
                updateSessionDetailsInCache(sessionId) { session ->
                    session.copy(name = request.name)
                }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update session name")
            }
    }

    override suspend fun updateSessionModel(
        sessionId: Long,
        request: UpdateSessionModelRequest
    ): Either<RepositoryError, Unit> {
        return sessionApi.updateSessionModel(sessionId, request)
            .map {
                updateSessionDetailsInCache(sessionId) { session ->
                    session.copy(currentModelId = request.modelId, currentSettingsId = null)
                }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update session model")
            }
    }

    override suspend fun updateSessionSettings(
        sessionId: Long,
        request: UpdateSessionSettingsRequest
    ): Either<RepositoryError, Unit> {
        return sessionApi.updateSessionSettings(sessionId, request)
            .map {
                updateSessionDetailsInCache(sessionId) { session ->
                    session.copy(currentSettingsId = request.settingsId)
                }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update session settings")
            }
    }

    override suspend fun updateSessionLeafMessage(
        sessionId: Long,
        request: UpdateSessionLeafMessageRequest
    ): Either<RepositoryError, Unit> {
        return sessionApi.updateSessionLeafMessage(sessionId, request)
            .map {
                updateSessionDetailsInCache(sessionId) { session ->
                    session.copy(currentLeafMessageId = request.leafMessageId)
                }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update session leaf message")
            }
    }

    override suspend fun updateSessionGroup(
        sessionId: Long,
        request: UpdateSessionGroupRequest
    ): Either<RepositoryError, Unit> {
        return sessionApi.updateSessionGroup(sessionId, request)
            .map {
                // Update summary list
                updateSessionInList(sessionId) { session ->
                    session.copy(groupId = request.groupId)
                }
                // Update details cache if present, as ChatSession also has groupId
                updateSessionDetailsInCache(sessionId) { session ->
                    session.copy(groupId = request.groupId)
                }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update session group")
            }
    }

    /**
     * Helper to update a ChatSessionSummary in the main `_sessions` StateFlow.
     * Only applies the update if the current state is DataState.Success.
     */
    private fun updateSessionInList(sessionId: Long, updateFunction: (ChatSessionSummary) -> ChatSessionSummary) {
        _sessions.update { currentState ->
            when (currentState) {
                is DataState.Success -> {
                    val updatedSessions = currentState.data.map { session ->
                        if (session.id == sessionId) updateFunction(session) else session
                    }
                    DataState.Success(updatedSessions)
                }

                else -> currentState // Keep other states (Loading, Error) unchanged
            }
        }
    }

    // --- Chat Message Operations ---

    override fun processNewMessage(
        sessionId: Long,
        clientEvents: Flow<ChatClientEvent>
    ): Flow<Either<RepositoryError, ChatEvent>> {
        return chatApi.processNewMessage(sessionId, clientEvents)
            .onEach { either ->
                either.map { event ->
                    applyEvent(sessionId, event)
                }
            }
            .map {
                it.mapLeft { err -> err.toRepositoryError("Failed to process new message") }
            }
    }

    override fun processNewMessageStreaming(
        sessionId: Long,
        clientEvents: Flow<ChatClientEvent>
    ): Flow<Either<RepositoryError, ChatStreamEvent>> {
        return chatApi.processNewMessageStreaming(sessionId, clientEvents)
            .onEach { either ->
                either.map { event ->
                    applyStreamEvent(sessionId, event)
                }
            }
            .map {
                it.mapLeft { err -> err.toRepositoryError("Failed to process new message") }
            }
    }

    override suspend fun updateMessageContent(
        messageId: Long,
        sessionId: Long,
        request: UpdateMessageRequest
    ): Either<RepositoryError, Unit> {
        return chatApi.updateMessageContent(messageId, request)
            .map { updatedMessage ->
                // Update the cached ChatSession with the new message
                updateSessionDetailsInCache(sessionId) { session ->
                    session.copy(messages = session.messages.map {
                        if (it.id == messageId) updatedMessage else it
                    })
                }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update message content")
            }
    }

    override suspend fun deleteMessage(messageId: Long, sessionId: Long): Either<RepositoryError, Unit> {
        return chatApi.deleteMessage(messageId)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to delete message")
            }
            .onRight {
                // Reload the session details to reflect the changes
                loadSessionDetails(sessionId)
            }
    }

    override suspend fun deleteMessageRecursively(messageId: Long, sessionId: Long): Either<RepositoryError, Unit> {
        return chatApi.deleteMessageRecursively(messageId)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to delete thread")
            }
            .onRight {
                // Reload the session details to reflect the changes
                loadSessionDetails(sessionId)
            }
    }

    override suspend fun insertMessage(
        sessionId: Long,
        targetMessageId: Long,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String,
        modelId: Long?,
        settingsId: Long?
    ): Either<RepositoryError, ChatMessage> {
        return chatApi.insertMessage(
            sessionId = sessionId,
            targetMessageId = targetMessageId,
            position = position,
            role = role,
            content = content,
            modelId = modelId,
            settingsId = settingsId
        )
            .mapLeft { it.toRepositoryError("Failed to insert message") }
            .onRight {
                // Reload session to reflect structural changes
                loadSessionDetails(sessionId)
            }
    }

    /**
     * Applies a non-streaming ChatEvent to update the cached session.
     * Handles events from the non-streaming message processing endpoint.
     */
    private suspend fun applyEvent(sessionId: Long, event: ChatEvent) {
        when (event) {
            is ChatEvent.UserMessageSaved -> {
                logger.debug("User message saved: ${event.userMessage.id}")
                updateSessionDetailsInCache(sessionId) { session ->
                    val updatedParent = event.updatedParentMessage
                    val updatedMessages = if (updatedParent != null) {
                        // Replace parent message with updated version
                        session.messages.map { msg ->
                            if (msg.id == updatedParent.id) {
                                updatedParent
                            } else {
                                msg
                            }
                        } + event.userMessage
                    } else {
                        session.messages + event.userMessage
                    }
                    session.copy(
                        messages = updatedMessages,
                        currentLeafMessageId = event.userMessage.id
                    )
                }
            }

            is ChatEvent.AssistantMessageSaved -> {
                logger.debug("Assistant message saved: ${event.assistantMessage.id}")
                updateSessionDetailsInCache(sessionId) { session ->
                    val updatedParent = event.updatedParentMessage
                    val updatedMessages = session.messages.map { msg ->
                        if (msg.id == updatedParent.id) {
                            updatedParent
                        } else {
                            msg
                        }
                    } + event.assistantMessage
                    session.copy(
                        messages = updatedMessages,
                        currentLeafMessageId = event.assistantMessage.id
                    )
                }
            }

            is ChatEvent.ToolCallsReceived -> {
                logger.debug("Tool calls received: ${event.toolCalls.size} calls")
                updateToolCallsInCache(sessionId, event.toolCalls)
            }

            is ChatEvent.LocalMCPToolCallReceived -> {
                logger.debug("Local MCP tool call received: ${event.request.toolName}")
            }

            is ChatEvent.ToolCallApprovalRequested -> {
                logger.debug("Tool call approval requested: ${event.toolCall.toolName}")
                // Update cache with tool call in AWAITING_APPROVAL status so UI can display it
                updateToolCallInCache(sessionId, event.toolCall)
            }

            is ChatEvent.ToolExecutionCompleted -> {
                logger.debug("Tool execution completed: ${event.toolCall.toolName} - ${event.toolCall.status}")
                updateToolCallInCache(sessionId, event.toolCall)
            }

            is ChatEvent.ErrorOccurred -> {
                logger.error("Chat event error: ${event.error}")
            }

            is ChatEvent.StreamCompleted -> {
                logger.info("Event stream completed for session $sessionId")
            }
        }
    }

    /**
     * Applies a streaming event to update the cached session.
     */
    private suspend fun applyStreamEvent(sessionId: Long, event: ChatStreamEvent) {
        when (event) {
            is ChatStreamEvent.UserMessageSaved -> {
                logger.debug("User message saved: ${event.userMessage.id}")
                updateSessionDetailsInCache(sessionId) { session ->
                    val updatedParent = event.updatedParentMessage
                    val updatedMessages = if (updatedParent != null) {
                        session.messages.map { msg ->
                            if (msg.id == updatedParent.id) {
                                updatedParent
                            } else {
                                msg
                            }
                        } + event.userMessage
                    } else {
                        session.messages + event.userMessage
                    }
                    session.copy(
                        messages = updatedMessages,
                        currentLeafMessageId = event.userMessage.id
                    )
                }
            }

            is ChatStreamEvent.AssistantMessageStart -> {
                logger.debug("Assistant message started: ${event.assistantMessage.id}")
                updateSessionDetailsInCache(sessionId) { session ->
                    val updatedParent = event.updatedParentMessage
                    val updatedMessages = session.messages.map { msg ->
                        if (msg.id == updatedParent.id) {
                            updatedParent
                        } else {
                            msg
                        }
                    } + event.assistantMessage
                    session.copy(
                        messages = updatedMessages,
                        currentLeafMessageId = event.assistantMessage.id
                    )
                }
            }

            is ChatStreamEvent.AssistantMessageDelta -> {
                logger.trace("Assistant message delta: ${event.deltaContent.length} chars")
                // Update the streaming message content
                updateSessionDetailsInCache(sessionId) { session ->
                    val updatedMessages = session.messages.map { message ->
                        if (message.id == event.messageId) {
                            val newContent = message.content + event.deltaContent
                            val now = Clock.System.now()
                            (message as ChatMessage.AssistantMessage).copy(content = newContent, updatedAt = now)
                        } else {
                            message
                        }
                    }
                    session.copy(messages = updatedMessages)
                }
            }

            is ChatStreamEvent.AssistantMessageEnd -> {
                logger.info("Assistant message completed: ${event.assistantMessage.id}")
                updateSessionDetailsInCache(sessionId) { session ->
                    val updatedMessages = session.messages.map { msg ->
                        if (msg.id == event.assistantMessage.id) {
                            event.assistantMessage
                        } else {
                            msg
                        }
                    }
                    session.copy(
                        messages = updatedMessages,
                        currentLeafMessageId = event.assistantMessage.id
                    )
                }
            }

            is ChatStreamEvent.ToolCallDelta -> {
                logger.trace("Tool call delta: ${event.name}")
                // Optional: Could accumulate for real-time display
                // For now, can be ignored - wait for ToolCallsReceived
            }

            is ChatStreamEvent.ToolCallsReceived -> {
                logger.debug("Tool calls received: ${event.toolCalls.size} calls")
                updateToolCallsInCache(sessionId, event.toolCalls)
            }

            is ChatStreamEvent.LocalMCPToolCallReceived -> {
                logger.debug("Local MCP tool call received: ${event.request.toolName}")
            }

            is ChatStreamEvent.ToolCallApprovalRequested -> {
                logger.debug("Tool call approval requested: ${event.toolCall.toolName}")
                // Update cache with tool call in AWAITING_APPROVAL status so UI can display it
                updateToolCallInCache(sessionId, event.toolCall)
            }

            is ChatStreamEvent.ToolExecutionCompleted -> {
                logger.debug("Tool execution completed: ${event.toolCall.toolName}")
                updateToolCallInCache(sessionId, event.toolCall)
            }

            is ChatStreamEvent.ErrorOccurred -> {
                logger.error("Streaming error: ${event.error}")
            }

            is ChatStreamEvent.StreamCompleted -> {
                logger.info("Streaming completed for session $sessionId")
            }
        }

    }

    /**
     * Helper to update a specific session's details flow in the `_sessionDetailsFlows` cache.
     * Only applies the update if the current state for that session ID is DataState.Success.
     *
     * @param sessionId The ID of the session to update
     * @param updateFunction A function that takes the current ChatSession and returns an updated one.
     */
    private suspend fun updateSessionDetailsInCache(sessionId: Long, updateFunction: (ChatSession) -> ChatSession) {
        _sessionDetailsFlowsMutex.withLock {
            _sessionDetailsFlows[sessionId]?.let { sessionFlow ->
                sessionFlow.update { currentState ->
                    when (currentState) {
                        is DataState.Success -> {
                            DataState.Success(updateFunction(currentState.data))
                        }

                        else -> {
                            logger.warn("Tried to update session $sessionId but it's not in Success state")
                            currentState // Keep other states (Loading, Error) unchanged for the individual flow
                        }
                    }
                }
            } ?: run {
                logger.warn("Tried to update session $sessionId but it's not in the cache")
                _sessionDetailsFlows.put(
                    sessionId, MutableStateFlow(
                        DataState.Error(RepositoryError.OtherError("Tried to update session $sessionId but it's not in the cache"))
                    )
                )
            }
        }
    }

    /**
     * Updates the tool calls cache for a session with a new list of tool calls for a specific message.
     *
     * @param sessionId The ID of the session containing the tool calls
     * @param toolCalls The list of tool calls to add to the cache
     */
    private suspend fun updateToolCallsInCache(sessionId: Long, toolCalls: List<ToolCall>) {
        _toolCallsFlowsMutex.withLock {
            _toolCallsFlows[sessionId]?.let { toolCallsFlow ->
                toolCallsFlow.update { currentState ->
                    when (currentState) {
                        is DataState.Success -> {
                            val sessionToolCalls = currentState.data
                            toolCalls.firstOrNull()?.messageId?.let { messageId ->
                                val updatedSessionToolCalls = sessionToolCalls + (messageId to toolCalls)
                                DataState.Success(updatedSessionToolCalls)
                            } ?: run {
                                logger.warn("Received empty list of tool calls")
                                currentState
                            }
                        }

                        else -> {
                            logger.warn("Tried to update tool calls for session $sessionId but flow is not in Success state")
                            currentState // Keep other states (Loading, Error) unchanged for the individual flow
                        }
                    }
                }
            } ?: run {
                logger.warn("Tried to update tool calls for session $sessionId but flow is not in the cache")
                _toolCallsFlows.put(
                    sessionId, MutableStateFlow(
                        DataState.Error(RepositoryError.OtherError("Tried to update tool calls for session $sessionId but flow is not in the cache"))
                    )
                )
            }
        }
    }

    /**
     * Updates a single tool call in the cache (typically after execution completes).
     *
     * @param sessionId The ID of the session containing the tool call
     * @param toolCall The updated tool call to replace the existing one in the cache
     */
    private suspend fun updateToolCallInCache(sessionId: Long, toolCall: ToolCall) {
        _toolCallsFlowsMutex.withLock {
            _toolCallsFlows[sessionId]?.let { toolCallsFlow ->
                toolCallsFlow.update { currentState ->
                    when (currentState) {
                        is DataState.Success -> {
                            val sessionToolCalls = currentState.data
                            val messageToolCalls = sessionToolCalls[toolCall.messageId] ?: emptyList()
                            val updatedMessageToolCalls = messageToolCalls.map {
                                if (it.id == toolCall.id) toolCall else it
                            }
                            val updatedSessionToolCalls =
                                sessionToolCalls + (toolCall.messageId to updatedMessageToolCalls)
                            DataState.Success(updatedSessionToolCalls)
                        }

                        else -> {
                            logger.warn("Tried to update tool call for session $sessionId but flow is not in Success state")
                            currentState // Keep other states (Loading, Error) unchanged for the individual flow
                        }
                    }
                }
            } ?: run {
                logger.warn("Tried to update tool call for session $sessionId but flow is not in the cache")
                _toolCallsFlows.put(
                    sessionId, MutableStateFlow(
                        DataState.Error(RepositoryError.OtherError("Tried to update tool call for session $sessionId but flow is not in the cache"))
                    )
                )
            }
        }
    }
}