package eu.torvian.chatbot.app.repository.impl.session

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Pins the tool-call store's contract: a session whose tool calls were never fetched is already loaded and empty,
 * because a session without tool calls must render without a loading state.
 */
class ToolCallStoreTest {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun toolCall(id: Long, messageId: Long) = ToolCall(
        id = id,
        messageId = messageId,
        toolDefinitionId = null,
        toolName = "spawn_agent",
        status = ToolCallStatus.PENDING,
        executedAt = now
    )

    @Test
    fun `an untouched session is loaded and empty, so it renders without a loading state`() = runTest {
        val store = ToolCallStore()

        assertEquals(DataState.Success(emptyMap()), store.flow(1L).value)
    }

    @Test
    fun `a mutation reaches the loaded tool calls`() = runTest {
        val store = ToolCallStore()
        store.markLoaded(1L, emptyMap())
        val call = toolCall(id = 7L, messageId = 5L)

        store.mutate(1L) { it + (5L to listOf(call)) }

        assertEquals(DataState.Success(mapOf(5L to listOf(call))), store.flow(1L).value)
    }

    @Test
    fun `a mutation for a session that was never loaded records an error entry instead of the tool calls`() = runTest {
        val store = ToolCallStore()

        store.mutate(2L) { it + (5L to emptyList()) }

        assertIs<DataState.Error<RepositoryError>>(store.flow(2L).value)
    }
}
