package eu.torvian.chatbot.app.repository.impl.session

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.core.ChatSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Pins the transcript store's contract: a session that has not been loaded is idle rather than empty, a mutation
 * reaches a loaded session, and a deleted session's transcript is dropped.
 */
class SessionDetailsStoreTest {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun session(name: String = "Session") = ChatSession(
        id = 1L,
        name = name,
        createdAt = now,
        updatedAt = now,
        groupId = null,
        agentRoleId = null,
        currentLeafMessageId = null,
        messages = emptyList()
    )

    @Test
    fun `an untouched session is idle, because its messages are not known yet`() = runTest {
        val store = SessionDetailsStore()

        assertEquals(DataState.Idle, store.flow(1L).value)
    }

    @Test
    fun `a mutation reaches a loaded session`() = runTest {
        val store = SessionDetailsStore()
        store.markLoaded(1L, session())

        store.mutate(1L) { it.copy(name = "Renamed") }

        assertEquals(DataState.Success(session("Renamed")), store.flow(1L).value)
    }

    @Test
    fun `a mutation for an unloaded session records an error entry instead of the session`() = runTest {
        val store = SessionDetailsStore()

        store.mutate(1L) { it.copy(name = "Renamed") }

        assertIs<DataState.Error<RepositoryError>>(store.flow(1L).value)
    }

    @Test
    fun `forget drops the transcript so the next access is idle again`() = runTest {
        val store = SessionDetailsStore()
        store.markLoaded(1L, session())

        store.forget(1L)

        assertEquals(DataState.Idle, store.flow(1L).value)
    }
}
