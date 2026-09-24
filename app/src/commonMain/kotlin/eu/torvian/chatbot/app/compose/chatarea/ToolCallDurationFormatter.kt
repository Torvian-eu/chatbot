package eu.torvian.chatbot.app.compose.chatarea

/**
 * Formats an elapsed duration using a compact unit selected from its raw millisecond value.
 * Nonnegative values use integer milliseconds below one second, seconds below one minute, and total minutes thereafter.
 * Seconds and minutes round half-up to one decimal place, omitting a zero fractional digit.
 * @param durationMs Elapsed duration in milliseconds.
 * @return The rounded duration and its `ms`, `s`, or `min` suffix.
 */
internal fun formatToolCallDuration(durationMs: Long): String {
    if (durationMs < 1_000L) return "${durationMs}ms"

    // Select the unit before rounding so a near-boundary value retains its raw-value unit.
    val divisor = if (durationMs < 60_000L) 100L else 6_000L
    val unit = if (durationMs < 60_000L) "s" else "min"
    val wholeTenths = durationMs / divisor
    val remainder = durationMs % divisor
    val roundedTenths = wholeTenths + if (remainder >= divisor / 2L) 1L else 0L
    val wholeUnits = roundedTenths / 10L
    val fractionalDigit = roundedTenths % 10L

    return buildString {
        append(wholeUnits)
        if (fractionalDigit != 0L) {
            append('.')
            append(fractionalDigit)
        }
        append(unit)
    }
}
