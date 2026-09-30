package eu.torvian.chatbot.app.service.misc

import eu.torvian.chatbot.app.domain.TurnOutcome
import eu.torvian.chatbot.app.domain.events.AppEvent
import eu.torvian.chatbot.app.domain.events.TurnLifecycleTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the non-suspending [EventBus.tryEmitEvent] path and the buffering that backs it.
 *
 * Publishers of turn alerts run in a turn's `finally`, which may already be cancelled, so publishing
 * must never suspend or throw; the buffer is what keeps a burst of alerts from being lost when the
 * subscribers are momentarily busy with an earlier event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventBusTest {

    /**
     * Buffer depth the behaviour tests rely on, derived from the bus so a change to it cannot
     * silently stop the overflow probe from proving anything.
     */
    private val bufferCapacity = EventBus.EVENT_BUFFER_CAPACITY

    /**
     * Builds a distinguishable trigger for a sequence number.
     *
     * @param index Sequence number.
     * @return Trigger identifying the sequence number in its session id.
     */
    private fun trigger(index: Int) = TurnLifecycleTrigger.TurnCompleted(
        sessionId = index.toLong(),
        outcome = TurnOutcome.SUCCESS
    )

    /**
     * Subscribes a trigger collector on the test scope and lets it start receiving.
     *
     * @receiver Test scope the collector runs in.
     * @param bus Bus to collect from.
     * @param received Sink the delivered triggers are appended to.
     */
    private fun TestScope.collectTriggers(bus: EventBus, received: MutableList<TurnLifecycleTrigger>) {
        // Filtering by type mirrors how consumers of the shared bus select what they handle.
        backgroundScope.launch {
            bus.events.filterIsInstance<TurnLifecycleTrigger>().collect { received.add(it) }
        }
        runCurrent()
    }

    @Test
    fun `tryEmitEvent delivers to a collector`() = runTest {
        val bus = EventBus()
        val received = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(bus, received)

        assertTrue(bus.tryEmitEvent(trigger(0)))
        runCurrent()

        assertEquals(listOf(trigger(0)), received.toList())
    }

    @Test
    fun `tryEmitEvent without a collector does not throw and reports acceptance`() = runTest {
        val bus = EventBus()

        // No collector is ever started: a publisher in a turn's "finally" must not fail or suspend,
        // and a value with nowhere to go is simply discarded (there is no replay).
        assertTrue(bus.tryEmitEvent(trigger(0)))
    }

    @Test
    fun `emitEvent still delivers alongside the non-suspending path`() = runTest {
        val bus = EventBus()
        val received = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(bus, received)

        bus.emitEvent(trigger(1))
        runCurrent()

        assertEquals(listOf(trigger(1)), received.toList())
    }

    @Test
    fun `a busy collector receives the buffered burst and the overflow is rejected`() = runTest {
        val bus = EventBus()
        val received = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(bus, received)

        // The scheduler is not advanced, so the collector cannot drain the buffer between publishes.
        val accepted = (0 until bufferCapacity + 8).count { bus.tryEmitEvent(trigger(it)) }

        assertEquals(bufferCapacity, accepted)
        assertFalse(bus.tryEmitEvent(trigger(bufferCapacity + 8)), "a full buffer must report the drop")

        runCurrent()

        // Everything accepted is delivered in order; the rejected events are gone rather than queued.
        assertEquals((0 until bufferCapacity).map { trigger(it) }, received.toList())
    }

    @Test
    fun `events of another type do not disturb a type-filtered consumer`() = runTest {
        val bus = EventBus()
        val received = mutableListOf<TurnLifecycleTrigger>()
        collectTriggers(bus, received)

        assertTrue(bus.tryEmitEvent(OtherEvent))
        assertTrue(bus.tryEmitEvent(trigger(0)))
        runCurrent()

        assertEquals(listOf(trigger(0)), received.toList())
    }

    /** Unrelated event type used to prove that consumers select what they handle by type. */
    private data object OtherEvent : AppEvent()
}
