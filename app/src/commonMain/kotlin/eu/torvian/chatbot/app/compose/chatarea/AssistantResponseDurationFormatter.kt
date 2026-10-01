package eu.torvian.chatbot.app.compose.chatarea

import kotlin.time.Duration

/**
 * Formats an elapsed turn duration as a two-field `mm:ss` clock value.
 *
 * Seconds are truncated towards zero, the minute field is zero-padded to at least two digits and grows
 * beyond that without an hours field, so long turns keep counting in minutes.
 *
 * @param elapsed Measured duration of a turn; negative values are treated as zero.
 * @return The elapsed value as `mm:ss`.
 */
internal fun formatAssistantResponseDuration(elapsed: Duration): String {
    // A backward wall-clock adjustment is the only way to observe a negative duration; showing time that
    // never elapsed would be misleading, so it renders as the zero value instead.
    val totalSeconds = elapsed.coerceAtLeast(Duration.ZERO).inWholeSeconds
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}
