package eu.torvian.chatbot.app.viewmodel.sessionstatus

import eu.torvian.chatbot.app.domain.TurnOutcome
import eu.torvian.chatbot.app.domain.events.TurnLifecycleTrigger
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.viewmodel.DefaultSessionSelectionController
import eu.torvian.chatbot.app.viewmodel.SessionSelectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the out-of-app alert triggers published by [InMemorySessionTurnStatusRegistry].
 *
 * The registry is the single point every turn reports its lifecycle to, so these tests pin down what
 * it publishes: one trigger per terminal turn, none for inconclusive endings, and one per transition
 * into the awaiting state rather than one per deferred tool call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemorySessionTurnStatusRegistryAlertTest {

    /**
     * Registry plus the bus its triggers are read from.
     *
     * @property registry Registry under test.
     * @property eventBus Bus the registry publishes on.
     */
    private data class Harness(
        val registry: InMemorySessionTurnStatusRegistry,
        val eventBus: EventBus
    )

    /**
     * Builds the harness with a selection observer on the test scope.
     *
     * @receiver Test scope providing the scheduler.
     * @param sessionSelectionController Selection state the registry observes.
     * @return Harness ready for use.
     */
    private fun TestScope.buildHarness(
        sessionSelectionController: SessionSelectionController = DefaultSessionSelectionController()
    ): Harness {
        val eventBus = EventBus()
        return Harness(
            registry = InMemorySessionTurnStatusRegistry(
                sessionSelectionController,
                eventBus,
                CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            ),
            eventBus = eventBus
        )
    }

    /**
     * Subscribes a trigger collector on the test scope and lets it start receiving.
     *
     * @receiver Test scope the collector runs in.
     * @param harness Harness whose bus is collected.
     * @param received Sink the delivered triggers are appended to.
     */
    private fun TestScope.collectTriggers(harness: Harness, received: MutableList<TurnLifecycleTrigger>) {
        backgroundScope.launch {
            harness.eventBus.events.filterIsInstance<TurnLifecycleTrigger>().collect { received.add(it) }
        }
        runCurrent()
    }

    @Test
    fun `finished turn publishes its outcome`() = runTest {
        val harness = buildHarness()
        val published = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(harness, published)

        harness.registry.onTurnStarted(7L)
        harness.registry.onTurnFinished(7L, TurnOutcome.SUCCESS)
        runCurrent()

        assertEquals(
            listOf(TurnLifecycleTrigger.TurnCompleted(7L, TurnOutcome.SUCCESS)),
            published.toList()
        )
    }

    @Test
    fun `failed turn publishes a failure trigger`() = runTest {
        val harness = buildHarness()
        val published = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(harness, published)

        harness.registry.onTurnFinished(7L, TurnOutcome.FAILURE)
        runCurrent()

        assertEquals(
            listOf(TurnLifecycleTrigger.TurnCompleted(7L, TurnOutcome.FAILURE)),
            published.toList()
        )
    }

    @Test
    fun `inconclusive turn publishes nothing`() = runTest {
        val harness = buildHarness()
        val published = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(harness, published)

        // Interrupted, cancelled and never-classified endings all report a null outcome.
        harness.registry.onTurnStarted(7L)
        harness.registry.onTurnFinished(7L, null)
        runCurrent()

        assertTrue(published.isEmpty())
    }

    @Test
    fun `completion is published even for the selected session`() = runTest {
        val controller = DefaultSessionSelectionController()
        val harness = buildHarness(controller)
        val published = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(harness, published)
        controller.selectSession(7L)

        harness.registry.onTurnStarted(7L)
        harness.registry.onTurnFinished(7L, TurnOutcome.SUCCESS)
        runCurrent()

        // The indicator badge is suppressed for the watched session, but the alert must still fire:
        // that is the case where the user has switched to another application.
        assertEquals(null, harness.registry.statuses.value[7L]?.indicator)
        assertEquals(
            listOf(TurnLifecycleTrigger.TurnCompleted(7L, TurnOutcome.SUCCESS)),
            published.toList()
        )
    }

    @Test
    fun `entering the awaiting state publishes one trigger`() = runTest {
        val harness = buildHarness()
        val published = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(harness, published)

        harness.registry.onTurnStarted(7L)
        harness.registry.onTurnAwaitingInput(7L, true)
        runCurrent()

        assertEquals(listOf(TurnLifecycleTrigger.AwaitingApproval(7L)), published.toList())
    }

    @Test
    fun `repeated reports while the same decision stays pending publish nothing`() = runTest {
        val harness = buildHarness()
        val published = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(harness, published)

        harness.registry.onTurnStarted(7L)
        // A turn deferring several tool calls at once reports the awaiting state once per call.
        harness.registry.onTurnAwaitingInput(7L, true)
        harness.registry.onTurnAwaitingInput(7L, true)
        harness.registry.onTurnAwaitingInput(7L, true)
        runCurrent()

        assertEquals(listOf(TurnLifecycleTrigger.AwaitingApproval(7L)), published.toList())
    }

    @Test
    fun `a later deferral after a decision publishes again`() = runTest {
        val harness = buildHarness()
        val published = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(harness, published)

        harness.registry.onTurnStarted(7L)
        harness.registry.onTurnAwaitingInput(7L, true)
        harness.registry.onTurnAwaitingInput(7L, false)
        harness.registry.onTurnAwaitingInput(7L, true)
        runCurrent()

        assertEquals(
            listOf(
                TurnLifecycleTrigger.AwaitingApproval(7L),
                TurnLifecycleTrigger.AwaitingApproval(7L)
            ),
            published.toList()
        )
    }

    @Test
    fun `a deferral in the next turn publishes again`() = runTest {
        val harness = buildHarness()
        val published = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(harness, published)

        harness.registry.onTurnStarted(7L)
        harness.registry.onTurnAwaitingInput(7L, true)
        harness.registry.onTurnFinished(7L, TurnOutcome.SUCCESS)
        harness.registry.onTurnStarted(7L)
        harness.registry.onTurnAwaitingInput(7L, true)
        runCurrent()

        assertEquals(
            listOf(
                TurnLifecycleTrigger.AwaitingApproval(7L),
                TurnLifecycleTrigger.TurnCompleted(7L, TurnOutcome.SUCCESS),
                TurnLifecycleTrigger.AwaitingApproval(7L)
            ),
            published.toList()
        )
    }

    @Test
    fun `clearing a completion flag publishes nothing`() = runTest {
        val harness = buildHarness()
        val published = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(harness, published)

        harness.registry.onTurnFinished(7L, TurnOutcome.SUCCESS)
        harness.registry.clearCompletion(7L)
        runCurrent()

        assertEquals(
            listOf(TurnLifecycleTrigger.TurnCompleted(7L, TurnOutcome.SUCCESS)),
            published.toList()
        )
    }
}
