package eu.torvian.chatbot.app.repository

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.*
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
    }

    private val _sessions = MutableStateFlow<DataState<RepositoryError, List<ChatSessionSummary>>>(DataState.Idle)
    override val sessions: StateFlow<DataState<RepositoryError, List<ChatSessionSummary>>> = _sessions.asStateFlow()

    // Cache for individual detailed session flows, guarded by a Mutex for thread safety
    private val _sessionDetailsFlowsMutex = Mutex()
    private val _sessionDetailsFlows: MutableMap<Long, MutableStateFlow<DataState<RepositoryError, ChatSession>>> =
        mutableMapOf()
    // TODO: Limit amount of cached flows to 10?

    override suspend fun getSessionDetailsFlow(sessionId: Long): StateFlow<DataState<RepositoryError, ChatSession>> {
        return _sessionDetailsFlowsMutex.withLock {
            _sessionDetailsFlows.getOrPut(sessionId) {
                // If not found, create a new flow initialized to Idle
                MutableStateFlow(DataState.Idle)
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
        // Get or create the MutableStateFlow for this sessionId, atomically.
        // This ensures the flow exists before we try to update it.
        val sessionFlow = _sessionDetailsFlowsMutex.withLock {
            _sessionDetailsFlows.getOrPut(sessionId) {
                MutableStateFlow(DataState.Idle)
            }
        }

        // Update the specific session flow to Loading
        sessionFlow.update { DataState.Loading }

        return sessionApi.getSessionDetails(sessionId)
            .map { sessionDetails ->
                sessionFlow.update { DataState.Success(sessionDetails) }
                sessionDetails
            }
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load session details")
                sessionFlow.update { DataState.Error(repositoryError) }
                repositoryError
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
                    session.copy(currentModelId = request.modelId)
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

    override suspend fun processNewMessage(
        sessionId: Long,
        request: ProcessNewMessageRequest
    ): Either<RepositoryError, Unit> {
        return chatApi.processNewMessage(sessionId, request)
            .map { newMessages ->
                // Update the cached ChatSession with the new messages
                updateSessionDetailsInCache(sessionId) { session ->
                    session.copy(messages = session.messages + newMessages)
                }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to process new message")
            }
    }

    override fun processNewMessageStreaming(
        sessionId: Long,
        request: ProcessNewMessageRequest
    ): Flow<Either<RepositoryError, ChatStreamEvent>> {
        return chatApi.processNewMessageStreaming(sessionId, request)
            .onEach { either ->
                either.map { event ->
                    applyStreamEvent(sessionId, event)
                }
            }
            .map {
                it.mapLeft { err -> err.toRepositoryError("Streaming Error") }
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
            .map {
                // Reload the session details to reflect the changes
                loadSessionDetails(sessionId)
                Unit
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to delete message")
            }
    }

    /**
     * Applies a streaming event to update the cached session.
     */
    private suspend fun applyStreamEvent(sessionId: Long, event: ChatStreamEvent) {
        when (event) {
            is ChatStreamEvent.UserMessageSaved -> {
                logger.debug("User message saved: ${event.message.id}")
                // Add the user message to the session's messages and update parent's children list
                updateSessionDetailsInCache(sessionId) { session ->
                    val updatedMessages = session.messages.map { message ->
                        if (message.id == event.message.parentMessageId) {
                            val newChildren = message.childrenMessageIds + event.message.id
                            val now = Clock.System.now()
                            when (message) {
                                is ChatMessage.UserMessage -> message.copy(
                                    childrenMessageIds = newChildren,
                                    updatedAt = now
                                )

                                is ChatMessage.AssistantMessage -> message.copy(
                                    childrenMessageIds = newChildren,
                                    updatedAt = now
                                )
                            }
                        } else {
                            message
                        }
                    } + event.message
                    session.copy(
                        messages = updatedMessages,
                        currentLeafMessageId = event.message.id
                    )
                }
            }

            is ChatStreamEvent.AssistantMessageStart -> {
                logger.debug("Assistant message started: ${event.assistantMessage.id}")
                // Add the assistant message to the session's messages
                updateSessionDetailsInCache(sessionId) { session ->
                    val updatedMessages = session.messages + event.assistantMessage
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
                logger.info("Assistant message completed: ${event.finalAssistantMessage.id}")
                // Remove the temporary messages from the session's messages and add the final ones
                updateSessionDetailsInCache(sessionId) { session ->
                    val updatedMessages = session.messages.filter {
                        it.id != event.tempMessageId && it.id != event.finalUserMessage.id
                    } + event.finalUserMessage + event.finalAssistantMessage
                    session.copy(
                        messages = updatedMessages,
                        currentLeafMessageId = event.finalAssistantMessage.id
                    )
                }
            }

            is ChatStreamEvent.ErrorOccurred -> {
                logger.error("Streaming error: ${event.error.message}")
            }

            ChatStreamEvent.StreamCompleted -> {
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
            _sessionDetailsFlows[sessionId]?.update { currentState ->
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
        }
    }
}