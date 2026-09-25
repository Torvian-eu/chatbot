package eu.torvian.chatbot.app.viewmodel.sessionstatus

import eu.torvian.chatbot.app.viewmodel.DefaultSessionSelectionController
import eu.torvian.chatbot.app.viewmodel.SessionSelectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [InMemorySessionTurnStatusRegistry]: indicator priority, turn-finish outcome handling,
 * the suppression of completion badges on the selected session, and clear-on-select semantics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemorySessionTurnStatusRegistryTest {

    /**
     * Creates a registry whose selection observer runs eagerly on the test scheduler.
     *
     * @receiver The surrounding test scope providing the scheduler.
     * @param sessionSelectionController Selection state the registry observes.
     * @return Registry ready for immediate use, without advancing the scheduler.
     */
    private fun TestScope.createRegistry(
        sessionSelectionController: SessionSelectionController = DefaultSessionSelectionController()
    ): InMemorySessionTurnStatusRegistry = InMemorySessionTurnStatusRegistry(
        sessionSelectionController,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    )

    @Test
    fun `started turn shows busy and awaiting input outranks it`() = runTest {
        val registry = createRegistry()

        registry.onTurnStarted(1L)
        assertEquals(SessionIndicator.BUSY, registry.statuses.value[1L]?.indicator)

        registry.onTurnAwaitingInput(1L, true)
        assertEquals(SessionIndicator.REQUESTING_INPUT, registry.statuses.value[1L]?.indicator)

        registry.onTurnAwaitingInput(1L, false)
        assertEquals(SessionIndicator.BUSY, registry.statuses.value[1L]?.indicator)
    }

    @Test
    fun `finished turn shows completion only when the session is not selected`() = runTest {
        val controller = DefaultSessionSelectionController()
        val registry = createRegistry(controller)
        controller.selectSession(2L)

        registry.onTurnStarted(1L)
        registry.onTurnFinished(1L, TurnOutcome.SUCCESS)
        assertEquals(SessionIndicator.COMPLETED_SUCCESS, registry.statuses.value[1L]?.indicator)

        registry.onTurnStarted(3L)
        registry.onTurnFinished(3L, TurnOutcome.FAILURE)
        assertEquals(SessionIndicator.COMPLETED_FAILURE, registry.statuses.value[3L]?.indicator)

        // The session being watched live never earns a completion badge.
        registry.onTurnStarted(2L)
        registry.onTurnFinished(2L, TurnOutcome.SUCCESS)
        assertNull(registry.statuses.value[2L]?.indicator)
    }

    @Test
    fun `interrupted or inconclusive turn ends without an indicator`() = runTest {
        val registry = createRegistry()

        registry.onTurnStarted(1L)
        registry.onTurnFinished(1L, null)
        assertNull(registry.statuses.value[1L]?.indicator)
    }

    @Test
    fun `selecting a session clears its completion flag but leaves live states`() = runTest {
        val controller = DefaultSessionSelectionController()
        val registry = createRegistry(controller)

        registry.onTurnStarted(1L)
        registry.onTurnFinished(1L, TurnOutcome.SUCCESS)
        registry.onTurnStarted(2L)
        registry.onTurnAwaitingInput(2L, true)

        controller.selectSession(1L)
        assertNull(registry.statuses.value[1L]?.indicator)
        assertEquals(SessionIndicator.REQUESTING_INPUT, registry.statuses.value[2L]?.indicator)

        // Live states remain visible on the selected row.
        controller.selectSession(2L)
        assertEquals(SessionIndicator.REQUESTING_INPUT, registry.statuses.value[2L]?.indicator)
    }

    @Test
    fun `clearCompletion clears only the completion flag`() = runTest {
        val registry = createRegistry()

        registry.onTurnStarted(1L)
        registry.onTurnFinished(1L, TurnOutcome.SUCCESS)
        registry.clearCompletion(1L)
        assertNull(registry.statuses.value[1L]?.indicator)

        // A live state survives the clear.
        registry.onTurnStarted(1L)
        registry.clearCompletion(1L)
        assertEquals(SessionIndicator.BUSY, registry.statuses.value[1L]?.indicator)
    }

    @Test
    fun `starting a new turn drops a leftover completion flag`() = runTest {
        val registry = createRegistry()

        registry.onTurnStarted(1L)
        registry.onTurnFinished(1L, TurnOutcome.SUCCESS)
        registry.onTurnStarted(1L)
        assertEquals(SessionIndicator.BUSY, registry.statuses.value[1L]?.indicator)

        // An interrupted follow-up turn must not resurrect the previous turn's completion.
        registry.onTurnFinished(1L, null)
        assertNull(registry.statuses.value[1L]?.indicator)
    }

    @Test
    fun `entries are dropped when status returns to default`() = runTest {
        val registry = createRegistry()

        registry.onTurnStarted(1L)
        registry.onTurnFinished(1L, null)
        registry.onTurnAwaitingInput(2L, true)
        registry.onTurnAwaitingInput(2L, false)

        assertEquals(emptyMap(), registry.statuses.value)
    }
}
