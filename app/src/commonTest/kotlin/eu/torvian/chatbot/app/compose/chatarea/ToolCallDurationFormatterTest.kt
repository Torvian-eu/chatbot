package eu.torvian.chatbot.app.compose.chatarea

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks compact duration units, threshold selection, and deterministic tenth rounding.
 */
class ToolCallDurationFormatterTest {

    /** Verifies sub-second values remain integer milliseconds and one second starts seconds. */
    @Test
    fun formatsSubSecondAndSecondBoundaries() {
        assertEquals("0ms", formatToolCallDuration(0L))
        assertEquals("999ms", formatToolCallDuration(999L))
        assertEquals("1s", formatToolCallDuration(1_000L))
    }

    /** Verifies seconds round half-up to tenths and omit a zero fractional digit. */
    @Test
    fun roundsSecondsToTenths() {
        assertEquals("1s", formatToolCallDuration(1_049L))
        assertEquals("1.1s", formatToolCallDuration(1_050L))
        assertEquals("1.2s", formatToolCallDuration(1_150L))
    }

    /** Verifies raw millisecond thresholds select seconds just below a minute and minutes at it. */
    @Test
    fun selectsUnitBeforeRoundingAtMinuteBoundary() {
        assertEquals("60s", formatToolCallDuration(59_999L))
        assertEquals("1min", formatToolCallDuration(60_000L))
        assertEquals("1min", formatToolCallDuration(60_001L))
    }

    /** Verifies minute values use half-up tenths and suppress an unnecessary decimal part. */
    @Test
    fun roundsMinutesToTenths() {
        assertEquals("1min", formatToolCallDuration(62_999L))
        assertEquals("1.1min", formatToolCallDuration(63_000L))
        assertEquals("1.1min", formatToolCallDuration(66_000L))
    }

    /** Verifies long durations remain total minutes and rounding handles the largest positive value. */
    @Test
    fun formatsLongDurationsWithoutHoursOrOverflow() {
        assertEquals("60min", formatToolCallDuration(3_600_000L))
        assertEquals("153722867280912.9min", formatToolCallDuration(Long.MAX_VALUE))
    }
}
