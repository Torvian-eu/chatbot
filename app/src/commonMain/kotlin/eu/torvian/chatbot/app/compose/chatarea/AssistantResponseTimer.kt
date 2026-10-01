package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.torvian.chatbot.app.viewmodel.chat.state.AssistantResponseTimerState
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Delay between live elapsed-time updates while a turn is active. */
private val ASSISTANT_RESPONSE_TIMER_TICK = 1.seconds

/** Alpha applied to a finished measurement so it reads as inactive next to a live one. */
private const val FROZEN_TIMER_ALPHA = 0.6f

/**
 * Non-interactive `mm:ss` indicator of how long the current assistant turn has been running.
 *
 * Renders nothing before the first turn of the session, a live value that updates once per second while a
 * turn is active, and the final value once the turn has ended. Only the value's own text node recomposes on
 * a tick, and the tick coroutine exists only for the live phase.
 *
 * @param state Measurement to display.
 * @param modifier Modifier applied to the value's text node.
 * @param nowProvider Source of the current instant; only tests pass a custom value so the live value can be
 *        advanced deterministically.
 */
@Composable
internal fun AssistantResponseTimer(
    state: AssistantResponseTimerState,
    modifier: Modifier = Modifier,
    nowProvider: () -> Instant = { Clock.System.now() }
) {
    when (state) {
        AssistantResponseTimerState.Hidden -> Unit

        is AssistantResponseTimerState.Frozen -> TimerValue(
            elapsed = state.elapsed,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = FROZEN_TIMER_ALPHA),
            modifier = modifier
        )

        is AssistantResponseTimerState.Running -> {
            // Recomputing from the start instant keeps the count correct when a tick is delayed or coalesced:
            // at most the display is one interval stale, and no elapsed time is lost.
            val elapsed by produceState(initialValue = nowProvider() - state.startedAt, state.startedAt) {
                while (true) {
                    delay(ASSISTANT_RESPONSE_TIMER_TICK)
                    value = nowProvider() - state.startedAt
                }
            }
            TimerValue(
                elapsed = elapsed,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier
            )
        }
    }
}

/**
 * Renders one timer value as plain text, without interactive semantics or an icon.
 *
 * @param elapsed Duration to format as `mm:ss`.
 * @param color Tint distinguishing a live measurement from a frozen one.
 * @param modifier Modifier applied to the text node.
 */
@Composable
private fun TimerValue(
    elapsed: Duration,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = formatAssistantResponseDuration(elapsed),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = color
    )
}
