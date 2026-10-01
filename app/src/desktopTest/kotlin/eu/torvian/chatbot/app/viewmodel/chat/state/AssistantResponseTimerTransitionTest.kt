package eu.torvian.chatbot.app.viewmodel.chat.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Pins the complete transition table of [AssistantResponseTimerState.advanceFor], which is the only place a
 * timer value can change.
 */
class AssistantResponseTimerTransitionTest {

    private val start = Instant.fromEpochSeconds(1_000L)

    private val running = AssistantResponseTimerState.Running(start)
    private val frozen = AssistantResponseTimerState.Frozen(30.seconds)

    /** Verifies a first turn records the transition instant as its start. */
    @Test
    fun `hidden starts a measurement when the turn starts running`() {
        assertEquals(
            running,
            AssistantResponseTimerState.Hidden.advanceFor(TurnExecutionState.RUNNING, start)
        )
    }

    /** Verifies a finished measurement is replaced by a fresh one when the next turn starts. */
    @Test
    fun `frozen is replaced by a new measurement when the turn starts running`() {
        assertEquals(
            running,
            frozen.advanceFor(TurnExecutionState.RUNNING, start)
        )
    }

    /** Verifies a repeated running write cannot restart an in-flight measurement. */
    @Test
    fun `running keeps its start instant on a repeated running write`() {
        assertEquals(running, running.advanceFor(TurnExecutionState.RUNNING, start + 30.seconds))
    }

    /** Verifies pausing and stopping leave the live measurement untouched so counting continues. */
    @Test
    fun `running keeps counting through pausing and stopping`() {
        assertEquals(running, running.advanceFor(TurnExecutionState.PAUSING, start + 10.seconds))
        assertEquals(running, running.advanceFor(TurnExecutionState.STOPPING, start + 20.seconds))
    }

    /** Verifies reaching idle freezes the elapsed time measured since the turn started. */
    @Test
    fun `running freezes the elapsed time when the turn becomes idle`() {
        assertEquals(
            AssistantResponseTimerState.Frozen(30.seconds),
            running.advanceFor(TurnExecutionState.IDLE, start + 30.seconds)
        )
    }

    /** Verifies a backward clock adjustment freezes at zero instead of a negative duration. */
    @Test
    fun `running freezes at zero when the clock moved backwards`() {
        assertEquals(
            AssistantResponseTimerState.Frozen(Duration.ZERO),
            running.advanceFor(TurnExecutionState.IDLE, start - 5.seconds)
        )
    }

    /** Verifies repeated idle writes cannot overwrite an already frozen measurement. */
    @Test
    fun `frozen survives repeated idle writes`() {
        assertEquals(frozen, frozen.advanceFor(TurnExecutionState.IDLE, start + 60.seconds))
    }

    /** Verifies a session without a turn stays hidden when the turn state is idle or intermediate. */
    @Test
    fun `hidden is unchanged by idle pausing and stopping`() {
        assertEquals(
            AssistantResponseTimerState.Hidden,
            AssistantResponseTimerState.Hidden.advanceFor(TurnExecutionState.IDLE, start)
        )
        assertEquals(
            AssistantResponseTimerState.Hidden,
            AssistantResponseTimerState.Hidden.advanceFor(TurnExecutionState.PAUSING, start)
        )
        assertEquals(
            AssistantResponseTimerState.Hidden,
            AssistantResponseTimerState.Hidden.advanceFor(TurnExecutionState.STOPPING, start)
        )
    }

    /** Verifies a frozen measurement is neither resumed nor cleared by an intermediate turn state. */
    @Test
    fun `frozen is unchanged by pausing and stopping`() {
        assertEquals(frozen, frozen.advanceFor(TurnExecutionState.PAUSING, start + 60.seconds))
        assertEquals(frozen, frozen.advanceFor(TurnExecutionState.STOPPING, start + 60.seconds))
    }
}
