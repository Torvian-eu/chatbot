package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.impl.session.SessionDetailsStore
import eu.torvian.chatbot.app.repository.impl.session.SessionSummaryStore
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.models.api.core.ThreadStats
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import kotlinx.coroutines.flow.StateFlow

/**
 * Default implementation of [SessionRepository]: the session list and its metadata.
 *
 * The implementation owns no state of its own. The rows it serves live in the injected [SessionSummaryStore]. Every
 * failure of [sessionApi] is translated into a [RepositoryError] naming the operation that failed.
 *
 * @property sessionApi The API client for session-related operations.
 * @property summaryStore The cached rows the list is served from and written to.
 * @property details The cached transcripts, so deleting a session also drops its transcript.
 */
internal class DefaultSessionListRepository(
    private val sessionApi: SessionApi,
    private val summaryStore: SessionSummaryStore,
    private val details: SessionDetailsStore
) : SessionRepository {

    override val sessions: StateFlow<DataState<RepositoryError, List<ChatSessionSummary>>> = summaryStore.sessions

    override suspend fun loadSessions(): Either<RepositoryError, Unit> {
        // A load already in flight owns the outcome, so a concurrent request is a no-op rather than a second fetch.
        if (!summaryStore.beginLoading()) return Unit.right()
        return sessionApi.getAllSessions()
            .map { sessionList ->
                summaryStore.replaceAll(sessionList)
            }
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load sessions")
                summaryStore.markError(repositoryError)
                repositoryError
            }
    }

    override suspend fun createSession(name: String): Either<RepositoryError, ChatSession> {
        return sessionApi.createSession(name)
            .map { newSession ->
                // Add the new session to the internal list of summaries
                summaryStore.append(
                    ChatSessionSummary(
                        id = newSession.id,
                        name = newSession.name,
                        groupId = newSession.groupId,
                        createdAt = newSession.createdAt,
                        updatedAt = newSession.updatedAt
                    )
                )

                // Get or create the flow for this new session and set its state to Success
                details.markLoaded(newSession.id, newSession)
                newSession
            }
            .mapLeft { it.toRepositoryError("Failed to create session") }
    }

    override suspend fun deleteSession(sessionId: Long): Either<RepositoryError, Unit> {
        return sessionApi.deleteSession(sessionId)
            .map {
                // Remove the session from the internal list of summaries
                summaryStore.remove(sessionId)

                // Remove the session's cached transcript, so it is not served from the cache any more
                details.forget(sessionId)
            }
            .mapLeft { it.toRepositoryError("Failed to delete session") }
    }

    override suspend fun updateSessionName(
        sessionId: Long,
        name: String
    ): Either<RepositoryError, Unit> {
        return sessionApi.updateSessionName(sessionId, name)
            .map {
                // Update summary list
                summaryStore.mutate(sessionId) { session ->
                    session.copy(name = name)
                }
                // Update details cache if present.
                details.mutate(sessionId) { session ->
                    session.copy(name = name)
                }
            }
            .mapLeft { it.toRepositoryError("Failed to update session name") }
    }

    override suspend fun updateSessionGroup(
        sessionId: Long,
        groupId: Long?
    ): Either<RepositoryError, Unit> {
        return sessionApi.updateSessionGroup(sessionId, groupId)
            .map {
                // Update summary list
                summaryStore.mutate(sessionId) { session ->
                    session.copy(groupId = groupId)
                }
                // Update details cache if present, as ChatSession also has groupId
                details.mutate(sessionId) { session ->
                    session.copy(groupId = groupId)
                }
            }
            .mapLeft { it.toRepositoryError("Failed to update session group") }
    }

    override suspend fun cloneSession(
        sessionId: Long,
        name: String
    ): Either<RepositoryError, ChatSession> {
        return sessionApi.cloneSession(sessionId, name)
            .mapLeft { it.toRepositoryError("Failed to clone session") }
            .onRight { clonedSession ->
                // Add the cloned session to the internal list of summaries
                summaryStore.append(
                    ChatSessionSummary(
                        id = clonedSession.id,
                        name = clonedSession.name,
                        groupId = clonedSession.groupId,
                        createdAt = clonedSession.createdAt,
                        updatedAt = clonedSession.updatedAt
                    )
                )

                // Get or create the flow for this new session and set its state to Success
                details.markLoaded(clonedSession.id, clonedSession)
            }

    }

    override suspend fun getThreadStats(sessionId: Long): Either<RepositoryError, ThreadStats> {
        return sessionApi.getThreadStats(sessionId)
            .mapLeft { it.toRepositoryError("Failed to load thread stats") }
    }
}
