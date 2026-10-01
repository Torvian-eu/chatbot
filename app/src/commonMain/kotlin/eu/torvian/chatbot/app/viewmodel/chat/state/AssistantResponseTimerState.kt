package eu.torvian.chatbot.app.viewmodel.chat.state

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Measurement of how long the active assistant turn has taken, as displayed by the composer timer.
 *
 * A live and a frozen measurement are mutually exclusive, so observers never have to decide which one takes
 * precedence, and freezing is a single atomic write for [kotlinx.coroutines.flow.StateFlow] readers.
 */
sealed class AssistantResponseTimerState {

    /** No turn has started since this session slot was loaded, so the composer renders no timer. */
    object Hidden : AssistantResponseTimerState()

    /**
     * A turn is active; its elapsed time derives from [startedAt] and keeps growing until the turn ends.
     *
     * @property startedAt Instant at which the turn entered [TurnExecutionState.RUNNING].
     */
    data class Running(val startedAt: Instant) : AssistantResponseTimerState()

    /**
     * A turn ended; [elapsed] is the final measurement and never changes again.
     *
     * @property elapsed Total measured duration of the finished turn.
     */
    data class Frozen(val elapsed: Duration) : AssistantResponseTimerState()
}

/**
 * Derives the timer value that follows the transition of the turn state machine into [turnState].
 *
 * Every timer change is driven by a turn-state change, and a refused send never leaves
 * [TurnExecutionState.IDLE], so the timer cannot be started by an action that does not start a turn.
 *
 * @param turnState Turn lifecycle state the machine has just entered.
 * @param now Instant at which the transition happened, used to record a start or to freeze an elapsed value.
 * @return The timer value to expose; may be the receiver when the transition leaves the measurement untouched.
 */
internal fun AssistantResponseTimerState.advanceFor(
    turnState: TurnExecutionState,
    now: Instant
): AssistantResponseTimerState = when {
    turnState == TurnExecutionState.RUNNING -> when (this) {
        // A repeated RUNNING write must keep the original start instant so the count is not restarted.
        is AssistantResponseTimerState.Running -> this
        // A finished measurement is replaced rather than extended, so the next turn starts from zero.
        else -> AssistantResponseTimerState.Running(now)
    }

    // Only a live measurement freezes: repeated IDLE writes must not overwrite an already frozen value,
    // and a hidden timer stays hidden until a turn actually starts.
    this is AssistantResponseTimerState.Running && turnState == TurnExecutionState.IDLE ->
        AssistantResponseTimerState.Frozen((now - startedAt).coerceAtLeast(Duration.ZERO))

    else -> this
}
