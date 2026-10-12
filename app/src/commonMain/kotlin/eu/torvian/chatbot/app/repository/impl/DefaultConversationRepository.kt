package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.ConversationRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolCallsMap
import eu.torvian.chatbot.app.repository.impl.session.SessionDetailsStore
import eu.torvian.chatbot.app.repository.impl.session.SessionEventApplier
import eu.torvian.chatbot.app.repository.impl.session.SessionSummaryStore
import eu.torvian.chatbot.app.repository.impl.session.ToolCallStore
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.CompactionEvent
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Default implementation of [ConversationRepository]: a session's transcript and its turn.
 *
 * The implementation owns no state of its own. The transcript lives in the injected [SessionDetailsStore], the tool
 * calls in the injected [ToolCallStore], the turn events are applied by the injected [SessionEventApplier], and a turn
 * that ends raises the session's ordering instant in the injected [SessionSummaryStore]. Every failure is translated
 * into a [RepositoryError].
 *
 * @property sessionApi The API client for session-related operations.
 * @property chatApi The API client for chat message operations.
 * @property details The cached transcripts the reads and mutations are served from.
 * @property toolCalls The cached tool calls the reads and event applications are served from.
 * @property eventApplier Applies the events of this client's sockets to [details] and [toolCalls].
 * @property summaryStore The cached rows a finished turn raises the ordering instant in.
 */
internal class DefaultConversationRepository(
    private val sessionApi: SessionApi,
    private val chatApi: ChatApi,
    private val details: SessionDetailsStore,
    private val toolCalls: ToolCallStore,
    private val eventApplier: SessionEventApplier,
    private val summaryStore: SessionSummaryStore
) : ConversationRepository {

    override suspend fun getSessionDetailsFlow(sessionId: Long): StateFlow<DataState<RepositoryError, ChatSession>> =
        details.flow(sessionId)

    override suspend fun getToolCallsFlow(sessionId: Long): StateFlow<DataState<RepositoryError, ToolCallsMap>> =
        toolCalls.flow(sessionId)

    override suspend fun loadSessionDetails(sessionId: Long): Either<RepositoryError, ChatSession> {
        details.markLoading(sessionId)
        return sessionApi.getSessionDetails(sessionId)
            .mapLeft { error ->
                val repositoryError = error.toRepositoryError("Failed to load session details")
                details.markError(sessionId, repositoryError)
                repositoryError
            }
            .onRight { sessionDetails ->
                details.markLoaded(sessionId, sessionDetails)
            }

    }

    override suspend fun loadSessionToolCalls(sessionId: Long): Either<RepositoryError, ToolCallsMap> {
        toolCalls.markLoading(sessionId)
        return sessionApi.getSessionToolCalls(sessionId)
            .mapLeft { error ->
                val repositoryError = error.toRepositoryError("Failed to load tool calls")
                toolCalls.markError(sessionId, repositoryError)
                repositoryError
            }
            .map { loadedToolCalls ->
                val toolCallsMap = loadedToolCalls.groupBy { it.messageId }
                toolCalls.markLoaded(sessionId, toolCallsMap)
                toolCallsMap
            }
    }

    override suspend fun markAssistantMessageInterrupted(sessionId: Long, messageId: Long) {
        // A local settlement of one message: the rest of the cached session is left exactly as it is, no
        // request is issued and the persisted state is not touched (the next load reads it authoritatively).
        details.mutate(sessionId) { session ->
            session.copy(
                messages = session.messages.map { message ->
                    // The guard keeps the server authoritative: a message that already carries a terminal state
                    // (its finalizing event arrived, or an earlier ending was already applied) must not be
                    // re-flagged, and only the placeholder this client stopped may be settled.
                    if (message.id == messageId &&
                        message is ChatMessage.AssistantMessage &&
                        !message.isComplete &&
                        message.incompleteCause == null
                    ) {
                        // Cause only: a user interruption has no error code and no reason text, the client
                        // localizes the label from the cause, and `updatedAt` stays the server's timestamp.
                        message.copy(incompleteCause = AssistantMessageIncompleteCause.INTERRUPTED_BY_USER)
                    } else {
                        message
                    }
                }
            )
        }
    }

    override suspend fun updateSessionAgentRole(
        sessionId: Long,
        agentRoleId: Long?
    ): Either<RepositoryError, Unit> {
        return sessionApi.updateSessionAgentRole(sessionId, agentRoleId)
            .map {
                details.mutate(sessionId) { session ->
                    session.copy(agentRoleId = agentRoleId)
                }
            }
            .mapLeft { it.toRepositoryError("Failed to update session agent role") }
    }

    override suspend fun updateSessionProject(
        sessionId: Long,
        projectId: Long?
    ): Either<RepositoryError, Unit> {
        return sessionApi.updateSessionProject(sessionId, projectId)
            .map { response ->
                // The response carries the server-computed result of the mutation, including the
                // role clear when the selected project made the pair illegal; the cache is updated
                // in one round-trip and no clear-rule logic is re-derived locally.
                details.mutate(sessionId) { session ->
                    session.copy(
                        projectId = response.projectId,
                        agentRoleId = response.agentRoleId
                    )
                }
            }
            .mapLeft { it.toRepositoryError("Failed to update session project") }
    }

    override suspend fun updateSessionLeafMessage(
        sessionId: Long,
        leafMessageId: Long?
    ): Either<RepositoryError, Unit> {
        return sessionApi.updateSessionLeafMessage(sessionId, leafMessageId)
            .map {
                details.mutate(sessionId) { session ->
                    session.copy(currentLeafMessageId = leafMessageId)
                }
            }
            .mapLeft { it.toRepositoryError("Failed to update session leaf message") }
    }

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

    override fun compactConversation(sessionId: Long): Flow<Either<RepositoryError, CompactionEvent>> {
        // Deliberately no event application: a compaction never mutates the transcript or the cached
        // session, so only the caller's notification layer consumes these events.
        return chatApi.compactConversation(sessionId)
            .map {
                it.mapLeft { err -> err.toRepositoryError("Failed to compact the conversation") }
            }
    }

    override suspend fun updateMessageContent(
        messageId: Long,
        sessionId: Long,
        content: String,
        fileReferences: List<FileReference>?
    ): Either<RepositoryError, Unit> {
        return chatApi.updateMessageContent(messageId, content, fileReferences)
            .map { updatedMessage ->
                // Update the cached ChatSession with the new message
                details.mutate(sessionId) { session ->
                    session.copy(messages = session.messages.map {
                        if (it.id == messageId) updatedMessage else it
                    })
                }
            }
            .mapLeft { it.toRepositoryError("Failed to update message content") }
    }

    override suspend fun deleteMessage(messageId: Long, sessionId: Long): Either<RepositoryError, Unit> {
        return chatApi.deleteMessage(messageId)
            .mapLeft { it.toRepositoryError("Failed to delete message") }
            .onRight {
                // Reload the session details to reflect the changes
                loadSessionDetails(sessionId)
            }
    }

    override suspend fun deleteMessageRecursively(messageId: Long, sessionId: Long): Either<RepositoryError, Unit> {
        return chatApi.deleteMessageRecursively(messageId)
            .mapLeft { it.toRepositoryError("Failed to delete thread") }
            .onRight {
                // Reload the session details to reflect the changes
                loadSessionDetails(sessionId)
            }
    }

    override suspend fun insertMessage(
        sessionId: Long,
        targetMessageId: Long?,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String,
        modelId: Long?,
        settingsId: Long?,
        fileReferences: List<FileReference>
    ): Either<RepositoryError, ChatMessage> {
        return chatApi.insertMessage(
            sessionId = sessionId,
            targetMessageId = targetMessageId,
            position = position,
            role = role,
            content = content,
            modelId = modelId,
            settingsId = settingsId,
            fileReferences = fileReferences
        )
            .mapLeft { it.toRepositoryError("Failed to insert message") }
            .onRight {
                // Reload session to reflect structural changes
                loadSessionDetails(sessionId)
            }
    }

    /**
     * Applies a non-streaming event to the cached session and raises that session's ordering instant.
     *
     * @param sessionId The session the event belongs to.
     * @param event The event to apply.
     */
    private suspend fun applyEvent(sessionId: Long, event: ChatEvent) {
        // A persisted message is activity for its session, and the persisted message's own server timestamp is the
        // ordering instant: no client clock and no extra request are involved.
        eventApplier.apply(sessionId, event)?.let { at -> summaryStore.raiseActivity(sessionId, at) }
    }

    /**
     * Applies a streaming event to the cached session and raises that session's ordering instant.
     *
     * @param sessionId The session the event belongs to.
     * @param event The event to apply.
     */
    private suspend fun applyStreamEvent(sessionId: Long, event: ChatStreamEvent) {
        // A persisted message is activity for its session, and the persisted message's own server timestamp is the
        // ordering instant: no client clock and no extra request are involved.
        eventApplier.apply(sessionId, event)?.let { at -> summaryStore.raiseActivity(sessionId, at) }
    }
}
