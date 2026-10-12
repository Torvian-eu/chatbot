package eu.torvian.chatbot.app.repository.impl.session

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Pins the cache rules the session stores rely on: an entry is created on first access in the configured initial
 * state, a value mutation only applies to a loaded entry, the named transitions drive the load states, and the entry
 * count stays bounded.
 */
class SessionFlowCacheTest {

    private fun cache(capacity: Int = 10, initialState: () -> DataState<RepositoryError, Int> = { DataState.Idle }) =
        SessionFlowCache(capacity, initialState)

    @Test
    fun `creates a missing entry in the configured initial state`() = runTest {
        val cache = cache(initialState = { DataState.Success(7) })

        assertEquals(DataState.Success(7), cache.flow(1L).value)
    }

    @Test
    fun `returns the same flow for the same session`() = runTest {
        val cache = cache()

        assertSame(cache.flow(1L), cache.flow(1L))
    }

    @Test
    fun `applies a value mutation to a loaded entry`() = runTest {
        val cache = cache(initialState = { DataState.Success(1) })
        cache.flow(1L)

        cache.mutate(1L) { it + 41 }

        assertEquals(DataState.Success(42), cache.flow(1L).value)
    }

    @Test
    fun `leaves an entry that is not loaded untouched`() = runTest {
        val cache = cache()
        cache.markLoading(1L)

        cache.mutate(1L) { it + 1 }

        assertEquals(DataState.Loading, cache.flow(1L).value)
    }

    @Test
    fun `records an error entry for a session that is not cached`() = runTest {
        val cache = cache()

        cache.mutate(1L) { it + 1 }

        val state = cache.flow(1L).value
        assertIs<DataState.Error<RepositoryError>>(state)
        assertIs<RepositoryError.OtherError>(state.error)
    }

    @Test
    fun `markLoading, markError and markLoaded drive the transitions of a missing entry`() = runTest {
        val cache = cache()

        cache.markLoading(1L)
        assertEquals(DataState.Loading, cache.flow(1L).value)

        val error = RepositoryError.OtherError("offline")
        cache.markError(1L, error)
        assertEquals(DataState.Error(error), cache.flow(1L).value)

        cache.markLoaded(1L, 5)
        assertEquals(DataState.Success(5), cache.flow(1L).value)
    }

    @Test
    fun `evicts the least recently used session once the capacity is reached`() = runTest {
        val cache = cache(capacity = 2, initialState = { DataState.Success(0) })
        cache.markLoaded(1L, 1)
        cache.markLoaded(2L, 2)

        // Touching the first session makes the second one the least recently used, and the third access evicts it.
        cache.flow(1L)
        cache.flow(3L)

        assertEquals(DataState.Success(1), cache.flow(1L).value)
        assertEquals(DataState.Success(0), cache.flow(2L).value)
    }

    @Test
    fun `forget drops the entry and the next access recreates it`() = runTest {
        val cache = cache(initialState = { DataState.Success(1) })
        cache.flow(1L)
        cache.mutate(1L) { 99 }

        cache.forget(1L)

        assertEquals(DataState.Success(1), cache.flow(1L).value)
    }
}
