package eu.torvian.chatbot.app.repository.impl.session

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.core.ChatSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the cached transcripts of the sessions this client has loaded.
 *
 * Every reader sees the transcript through [flow], a read-only view, and every write is one of the named transitions
 * below, so the two rules of the cache cannot be bypassed from outside: a transcript is only ever transformed while
 * it is loaded, and a transformation for a session that is not cached records an error entry instead of being
 * half-applied. A newly touched session starts in [DataState.Idle], because its messages are only known once it has
 * been loaded.
 */
internal class SessionDetailsStore {

    /** The cached transcripts, bounded so a long session list cannot grow the memory without limit. */
    private val cache = SessionFlowCache<ChatSession>(CAPACITY) { DataState.Idle }

    /**
     * Returns a read-only view of the transcript of [sessionId], empty and idle until it has been loaded.
     *
     * @param sessionId The session whose transcript is read.
     * @return The cached transcript flow, which cannot be written through.
     */
    suspend fun flow(sessionId: Long): StateFlow<DataState<RepositoryError, ChatSession>> = cache.flow(sessionId)

    /**
     * Reports that the transcript of [sessionId] is being loaded.
     *
     * @param sessionId The session whose read started.
     */
    suspend fun markLoading(sessionId: Long) {
        cache.markLoading(sessionId)
    }

    /**
     * Reports that the transcript of [sessionId] could not be loaded.
     *
     * @param sessionId The session whose read failed.
     * @param error The failure to report.
     */
    suspend fun markError(sessionId: Long, error: RepositoryError) {
        cache.markError(sessionId, error)
    }

    /**
     * Replaces the transcript of [sessionId] with [session], which is what a completed load, a create and a clone
     * all produce.
     *
     * @param sessionId The session whose transcript is now known in full.
     * @param session The session the cache holds.
     */
    suspend fun markLoaded(sessionId: Long, session: ChatSession) {
        cache.markLoaded(sessionId, session)
    }

    /**
     * Replaces the transcript of [sessionId] with the result of [transform], leaving the session untouched when it
     * is not loaded.
     *
     * @param sessionId The session whose transcript is transformed.
     * @param transform The transformation to apply to the loaded session.
     */
    suspend fun mutate(sessionId: Long, transform: (ChatSession) -> ChatSession) {
        cache.mutate(sessionId, transform)
    }

    /**
     * Drops the transcript of [sessionId], so a session that no longer exists is never served from the cache.
     *
     * @param sessionId The session to forget.
     */
    suspend fun forget(sessionId: Long) {
        cache.forget(sessionId)
    }

    private companion object {
        /** Forty transcripts cover a long session list without holding every session ever opened. */
        const val CAPACITY = 40
    }
}
