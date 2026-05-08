package eu.torvian.chatbot.app.utils.ui

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Formats a timestamp as a human-friendly relative time string.
 *
 * @param instant The point in time to describe.
 * @return A short relative description such as "Just now" or "5 minutes ago".
 */
fun formatRelativeTime(instant: Instant): String {
    val duration = Clock.System.now() - instant

    return when {
        duration < 1.minutes -> "Just now"
        duration < 1.hours -> "${duration.inWholeMinutes} minute${pluralSuffix(duration.inWholeMinutes)} ago"
        duration < 1.days -> "${duration.inWholeHours} hour${pluralSuffix(duration.inWholeHours)} ago"
        else -> "${duration.inWholeDays} day${pluralSuffix(duration.inWholeDays)} ago"
    }
}

/**
 * Returns a plural suffix for small relative-time labels.
 *
 * @param value The numeric quantity being rendered.
 * @return An empty string for singular values, otherwise "s".
 */
fun pluralSuffix(value: Long): String = if (value == 1L) "" else "s"
