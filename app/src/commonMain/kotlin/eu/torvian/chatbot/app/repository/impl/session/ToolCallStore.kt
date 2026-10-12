package eu.torvian.chatbot.app.repository.impl.session

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolCallsMap
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the cached tool calls of the sessions this client has loaded, grouped by the message they belong to.
 *
 * Every reader sees the tool calls through [flow], a read-only view, and every write is one of the named transitions
 * below, so the cache rules cannot be bypassed from outside. Unlike a transcript, a newly touched session starts
 * loaded and empty: a session without tool calls must render without a loading state, so an empty map is already the
 * correct answer.
 */
internal class ToolCallStore {

    /** The cached tool-call maps, bounded so a long session list cannot grow the memory without limit. */
    private val cache = SessionFlowCache<ToolCallsMap>(CAPACITY) { DataState.Success(emptyMap()) }

    /**
     * Returns a read-only view of the tool calls of [sessionId], loaded and empty until they have been fetched.
     *
     * @param sessionId The session whose tool calls are read.
     * @return The cached tool-call flow, which cannot be written through.
     */
    suspend fun flow(sessionId: Long): StateFlow<DataState<RepositoryError, ToolCallsMap>> = cache.flow(sessionId)

    /**
     * Reports that the tool calls of [sessionId] are being fetched.
     *
     * @param sessionId The session whose fetch started.
     */
    suspend fun markLoading(sessionId: Long) {
        cache.markLoading(sessionId)
    }

    /**
     * Reports that the tool calls of [sessionId] could not be fetched.
     *
     * @param sessionId The session whose fetch failed.
     * @param error The failure to report.
     */
    suspend fun markError(sessionId: Long, error: RepositoryError) {
        cache.markError(sessionId, error)
    }

    /**
     * Replaces the tool calls of [sessionId] with [toolCalls].
     *
     * @param sessionId The session whose tool calls are now known.
     * @param toolCalls The tool calls the cache holds.
     */
    suspend fun markLoaded(sessionId: Long, toolCalls: ToolCallsMap) {
        cache.markLoaded(sessionId, toolCalls)
    }

    /**
     * Replaces the tool calls of [sessionId] with the result of [transform], leaving them untouched when they are
     * not loaded.
     *
     * @param sessionId The session whose tool calls are transformed.
     * @param transform The transformation to apply to the loaded tool calls.
     */
    suspend fun mutate(sessionId: Long, transform: (ToolCallsMap) -> ToolCallsMap) {
        cache.mutate(sessionId, transform)
    }

    private companion object {
        /** Matches the transcript cache, so a session is either cached with both or with neither. */
        const val CAPACITY = 40
    }
}
