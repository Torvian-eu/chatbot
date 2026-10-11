package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.tool_call_construction_indicator
import eu.torvian.chatbot.app.viewmodel.chat.state.isToolCallConstructionActive
import eu.torvian.chatbot.common.models.tool.ToolCall
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Delay between freshness re-evaluations while a tool call is being written.
 *
 * The label may therefore linger for up to about one tick past the freshness window, which is unnoticeable while
 * the message is still streaming; no tick runs once the window has lapsed.
 */
private val TOOL_CALL_CONSTRUCTION_TICK = 1.seconds

/**
 * The tool-call slot of an assistant message: either the construction indicator or the badges of the calls that
 * are already known.
 *
 * The two contents are mutually exclusive by construction, so a message can never show both, and both are
 * rendered after the same leading spacer. That keeps the slot's position stable while a call is written and then
 * replaced by its badges, and lets a message with neither show nothing at all rather than a stray gap.
 *
 * @param toolCalls Tool calls already received for the message; empty while only deltas have arrived.
 * @param lastDeltaAt Instant of the message's most recent tool-call delta, or `null` when none was recorded.
 * @param onToolCallClick Invoked with the tool call selected by the user.
 * @param modifier Modifier applied to the rendered indicator or badge group.
 * @param nowProvider Source of the current instant; only tests pass a custom value so expiry can be advanced
 *        deterministically.
 */
@Composable
internal fun ToolCallSection(
    toolCalls: List<ToolCall>,
    lastDeltaAt: Instant?,
    onToolCallClick: (ToolCall) -> Unit,
    modifier: Modifier = Modifier,
    nowProvider: () -> Instant = { Clock.System.now() }
) {
    val isConstructing by rememberToolCallConstructionActive(lastDeltaAt, nowProvider)

    if (!isConstructing && toolCalls.isEmpty()) return

    Spacer(Modifier.height(8.dp))
    if (isConstructing) {
        ToolCallConstructionIndicator(modifier = modifier)
    } else {
        ToolCallBadges(
            toolCalls = toolCalls,
            onToolCallClick = onToolCallClick,
            modifier = modifier
        )
    }
}

/**
 * Tracks whether a tool call is still being written, re-evaluating once per [TOOL_CALL_CONSTRUCTION_TICK].
 *
 * The value is derived from the recorded instant rather than accumulated, so a delayed or coalesced tick only
 * leaves it one interval stale, and the producer stops as soon as the window lapses or no record exists — no
 * loop runs while nothing is fresh.
 *
 * @param lastDeltaAt Instant of the most recent tool-call delta, or `null` when none was recorded.
 * @param nowProvider Source of the current instant.
 * @return Current freshness, recomputed until it becomes `false`.
 */
@Composable
private fun rememberToolCallConstructionActive(
    lastDeltaAt: Instant?,
    nowProvider: () -> Instant
): State<Boolean> = produceState(
    initialValue = isToolCallConstructionActive(nowProvider(), lastDeltaAt),
    lastDeltaAt
) {
    // The producer's first assignment is required: the state survives a key change, so a new record would
    // otherwise be hidden behind the previous, already lapsed value until the first tick.
    value = isToolCallConstructionActive(nowProvider(), lastDeltaAt)
    while (value) {
        delay(TOOL_CALL_CONSTRUCTION_TICK)
        value = isToolCallConstructionActive(nowProvider(), lastDeltaAt)
    }
}

/**
 * Static label announcing that the model is currently writing tool calls for a message.
 *
 * Styled like the badge group's own label so the two read as the same slot, and intentionally static: no spinner,
 * no animation and no partial argument text, which would render a reconstruction of the call rather than the call.
 *
 * @param modifier Modifier applied to the label.
 */
@Composable
private fun ToolCallConstructionIndicator(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.tool_call_construction_indicator),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    )
}
