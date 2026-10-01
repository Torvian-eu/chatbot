package eu.torvian.chatbot.server.service.core.chat.turn

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Whole milliseconds elapsed between [startedAt] and now, following the project's wall-clock time convention.
 *
 * The elapsed time is clamped at zero because the source is a wall clock that can move backwards; a negative
 * duration must never be persisted. The result is one measurement of one provider call, taken at the call site.
 *
 * @param startedAt Wall-clock instant captured immediately before the measured call was dispatched.
 * @return Non-negative elapsed milliseconds.
 */
internal fun elapsedMillisecondsSince(startedAt: Instant): Long =
    (Clock.System.now() - startedAt).inWholeMilliseconds.coerceAtLeast(0)
