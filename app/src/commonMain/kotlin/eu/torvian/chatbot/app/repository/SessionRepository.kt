package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.core.ThreadStats
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for the chat session list and its metadata.
 *
 * This is the list half of the session API: the summaries the session panel is rendered from, the create, clone,
 * rename, group and delete operations behind it, and the aggregate statistics of one session. The transcript and the
 * turn live on [ConversationRepository]. The two exist apart because they serve different customers and hold different
 * caches, so a caller depends only on the half it uses. A member belongs to the half whose cache it writes: an
 * operation that only updates the cached session belongs to [ConversationRepository].
 *
 * Implementations expose the summaries as a reactive `StateFlow` and translate every API failure into the
 * [RepositoryError] hierarchy.
 */
interface SessionRepository {

    /**
     * Reactive stream of all chat session summaries.
     *
     * This StateFlow provides real-time updates whenever the session data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of all session summaries wrapped in DataState
     */
    val sessions: StateFlow<DataState<RepositoryError, List<ChatSessionSummary>>>

    /**
     * Loads all chat session summaries from the backend.
     *
     * This operation fetches the latest session data and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadSessions(): Either<RepositoryError, Unit>

    /**
     * Creates a new chat session.
     *
     * Upon successful creation, the new session is automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param name Required non-blank session name
     * @return Either.Right with the created ChatSession on success, or Either.Left with RepositoryError on failure
     */
    suspend fun createSession(name: String): Either<RepositoryError, ChatSession>

    /**
     * Deletes a chat session and all its associated messages.
     *
     * Upon successful deletion, the session is automatically removed from the internal
     * StateFlows, triggering updates to all observers.
     *
     * @param sessionId The unique identifier of the session to delete
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteSession(sessionId: Long): Either<RepositoryError, Unit>

    /**
     * Updates the name of a specific chat session.
     *
     * Upon successful update, the modified session replaces the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param sessionId The unique identifier of the session to rename
     * @param name The new name for the session
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateSessionName(sessionId: Long, name: String): Either<RepositoryError, Unit>

    /**
     * Assigns a specific chat session to a chat group, or ungroups it.
     *
     * Upon successful update, the modified session replaces the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param sessionId The unique identifier of the session to assign
     * @param groupId The new optional group ID for the session
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateSessionGroup(sessionId: Long, groupId: Long?): Either<RepositoryError, Unit>

    /**
     * Clones an existing chat session with all its messages, tool calls, and configuration.
     *
     * Upon successful creation, the new session is automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param sessionId The unique identifier of the session to clone
     * @param name The name for the cloned session
     * @return Either.Right with the cloned ChatSession on success, or Either.Left with RepositoryError on failure
     */
    suspend fun cloneSession(sessionId: Long, name: String): Either<RepositoryError, ChatSession>

    /**
     * Fetches the aggregate statistics of the session's currently displayed thread.
     *
     * The call is deliberately uncached and holds no state of its own: the statistics describe the
     * conversation as it is at the moment of the request, so every reader fetches them anew instead of
     * being served a stale aggregate.
     *
     * @param sessionId The unique identifier of the session whose thread is measured
     * @return Either.Right with the [ThreadStats] of the current branch, or Either.Left with RepositoryError
     */
    suspend fun getThreadStats(sessionId: Long): Either<RepositoryError, ThreadStats>
}
