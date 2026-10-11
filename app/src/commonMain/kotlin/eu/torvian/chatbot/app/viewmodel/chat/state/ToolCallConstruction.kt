package eu.torvian.chatbot.app.viewmodel.chat.state

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How long a message keeps showing the tool-call construction indicator after its most recent tool-call delta.
 *
 * A fixed compile-time budget rather than a configurable value: the indicator describes the model still
 * writing one call, and a shorter or longer window would only trade a flicker for a stale label.
 */
internal val TOOL_CALL_CONSTRUCTION_WINDOW: Duration = 5.seconds

/**
 * Whether the tool call being written for a message is still considered in progress at [now].
 *
 * The window is rolling: it is measured from the most recent delta, so a continuing stream keeps the
 * indicator alive while a stalled one lets it lapse. The comparison is strict, so a record exactly one
 * window old already counts as stale.
 *
 * @param now Instant the freshness is evaluated at.
 * @param lastDeltaAt Instant of the message's most recent tool-call delta, or `null` when none was recorded.
 * @return `true` while the elapsed time since [lastDeltaAt] is shorter than [TOOL_CALL_CONSTRUCTION_WINDOW].
 */
internal fun isToolCallConstructionActive(now: Instant, lastDeltaAt: Instant?): Boolean =
    lastDeltaAt != null && now - lastDeltaAt < TOOL_CALL_CONSTRUCTION_WINDOW
