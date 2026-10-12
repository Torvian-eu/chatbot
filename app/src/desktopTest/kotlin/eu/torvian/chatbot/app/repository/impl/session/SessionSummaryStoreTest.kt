package eu.torvian.chatbot.app.repository.impl.session

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Pins the two rules the session list depends on: a row write never leaves the loaded state, and the ordering
 * instant of a row never moves backwards.
 */
class SessionSummaryStoreTest {

    private val base = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun at(offsetMillis: Long) = Instant.fromEpochMilliseconds(base.toEpochMilliseconds() + offsetMillis)

    private fun summary(id: Long, updatedAt: Instant = base, name: String = "Session $id") = ChatSessionSummary(
        id = id,
        name = name,
        createdAt = base,
        updatedAt = updatedAt,
        groupId = null
    )

    /** The stored rows of a store that was loaded with [rows], failing the case when the list is not loaded. */
    private fun storeWith(vararg rows: ChatSessionSummary): SessionSummaryStore {
        val store = SessionSummaryStore()
        store.replaceAll(rows.toList())
        return store
    }

    private fun rowsOf(store: SessionSummaryStore): List<ChatSessionSummary> =
        store.sessions.value.dataOrNull ?: error("the session list was expected to be loaded")

    @Test
    fun `raise activity keeps the newer instant`() = runTest {
        val store = storeWith(summary(1L, at(1_000)))

        store.raiseActivity(1L, at(5_000))

        assertEquals(at(5_000), rowsOf(store).single().updatedAt)
    }

    @Test
    fun `raise activity never lowers the instant`() = runTest {
        val store = storeWith(summary(1L, at(5_000)))

        store.raiseActivity(1L, at(1_000))

        assertEquals(at(5_000), rowsOf(store).single().updatedAt)
    }

    @Test
    fun `a row write after a failed load leaves the error state in place`() = runTest {
        val store = SessionSummaryStore()
        store.beginLoading()
        store.markError(RepositoryError.OtherError("offline"))

        store.append(summary(1L))
        store.mutate(1L) { it.copy(name = "Renamed") }
        store.remove(1L)

        assertTrue(store.sessions.value.isError, "a row write must not resurrect a failed load")
    }

    @Test
    fun `mutate and remove only touch the addressed row`() = runTest {
        val store = storeWith(summary(1L), summary(2L))

        store.mutate(1L) { it.copy(name = "Renamed") }

        assertEquals("Renamed", rowsOf(store).single { it.id == 1L }.name)
        assertEquals("Session 2", rowsOf(store).single { it.id == 2L }.name)

        store.remove(1L)

        assertEquals(listOf(2L), rowsOf(store).map { it.id })
    }

    @Test
    fun `replace all makes the list observable as loaded`() = runTest {
        val store = SessionSummaryStore()

        store.beginLoading()
        store.replaceAll(listOf(summary(1L)))

        assertEquals(DataState.Success(listOf(summary(1L))), store.sessions.value)
    }

    @Test
    fun `begin loading is refused while a load is already in flight`() = runTest {
        val store = SessionSummaryStore()

        assertTrue(store.beginLoading(), "the first call starts the load")
        assertFalse(store.beginLoading(), "a call during a running load must not start a second one")
    }
}
