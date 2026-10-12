package eu.torvian.chatbot.app.repository.impl.session

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.utils.misc.LruCache
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the per-session flows of one kind of cached session data, with the entry count bounded by an LRU cache.
 *
 * Only the transitions a holder may legally make are exposed: a raw state write is not, so no holder can put an entry
 * into a state the rules forbid, and readers cannot write through the flow they receive. The rules every caller
 * relies on live here instead of being repeated per call site: an entry is created on first access in
 * [initialState], and a value mutation is applied only while the entry is [DataState.Success]. For a session that is
 * not cached at all a mutation records an error entry instead, which keeps such a mutation harmless for a session
 * nothing renders, and the next load is what makes the session visible again.
 *
 * Each instance owns its lock, so the flows of one kind never wait on the traffic of another kind.
 *
 * @param V The cached value type, one per kind of session data.
 * @param capacity The maximum number of cached sessions; the least recently used entry is evicted beyond it.
 * @param initialState The state a newly created entry starts in.
 */
internal class SessionFlowCache<V>(
    capacity: Int,
    private val initialState: () -> DataState<RepositoryError, V>
) {

    /** The session-keyed flows; their LRU order is what bounds the memory of a long session list. */
    private val flows = LruCache<Long, MutableStateFlow<DataState<RepositoryError, V>>>(capacity)

    /** Guards [flows] and every transition driven through this cache. */
    private val mutex = Mutex()

    /**
     * Returns a read-only view of the flow of [sessionId], creating it in [initialState] when it is not cached yet.
     *
     * @param sessionId The session whose flow is read.
     * @return The cached flow, which is never null and cannot be written through.
     */
    suspend fun flow(sessionId: Long): StateFlow<DataState<RepositoryError, V>> = mutex.withLock {
        entry(sessionId)
    }

    /**
     * Transitions the flow of [sessionId] into [DataState.Loading], creating the entry when it is absent.
     *
     * @param sessionId The session whose read started.
     */
    suspend fun markLoading(sessionId: Long) {
        mutex.withLock { entry(sessionId).update { DataState.Loading } }
    }

    /**
     * Transitions the flow of [sessionId] into [DataState.Error], creating the entry when it is absent.
     *
     * @param sessionId The session whose read failed.
     * @param error The failure to report.
     */
    suspend fun markError(sessionId: Long, error: RepositoryError) {
        mutex.withLock { entry(sessionId).update { DataState.Error(error) } }
    }

    /**
     * Transitions the flow of [sessionId] into [DataState.Success] with [value], creating the entry when it is
     * absent.
     *
     * @param sessionId The session whose value is now known.
     * @param value The value the cache holds.
     */
    suspend fun markLoaded(sessionId: Long, value: V) {
        mutex.withLock { entry(sessionId).update { DataState.Success(value) } }
    }

    /**
     * Applies [transform] to the cached value of [sessionId] while its state is [DataState.Success].
     *
     * An entry in any other state holds no loaded value to transform and keeps its state. A session that is not
     * cached at all gets an error entry, so a mutation that arrives before the session is loaded leaves nothing
     * half-applied behind.
     *
     * @param sessionId The session whose cached value is transformed.
     * @param transform The value transformation to apply.
     */
    suspend fun mutate(sessionId: Long, transform: (V) -> V) {
        mutex.withLock {
            val sessionFlow = flows[sessionId]
            if (sessionFlow == null) {
                logger.warn("Tried to update session $sessionId but it's not in the cache")
                flows.put(
                    sessionId,
                    MutableStateFlow(
                        DataState.Error(
                            RepositoryError.OtherError("Tried to update session $sessionId but it's not in the cache")
                        )
                    )
                )
                return@withLock
            }
            sessionFlow.update { currentState ->
                when (currentState) {
                    is DataState.Success -> DataState.Success(transform(currentState.data))
                    else -> {
                        logger.warn("Tried to update session $sessionId but it's not in Success state")
                        currentState
                    }
                }
            }
        }
    }

    /**
     * Drops the cached flow of [sessionId], so the next access creates a fresh one in [initialState].
     *
     * @param sessionId The session to forget.
     */
    suspend fun forget(sessionId: Long) {
        mutex.withLock { flows.remove(sessionId) }
    }

    /**
     * Returns the flow of [sessionId], inserting a new one in [initialState] when it is absent.
     *
     * @param sessionId The session to look up.
     * @return The cached flow, which is never null.
     */
    private fun entry(sessionId: Long): MutableStateFlow<DataState<RepositoryError, V>> =
        flows.getOrPut(sessionId) { MutableStateFlow(initialState()) }

    companion object {
        /** Reports the mutations that could not be applied, so a cache miss is visible while diagnosing. */
        private val logger = kmpLogger<SessionFlowCache<*>>()
    }
}
