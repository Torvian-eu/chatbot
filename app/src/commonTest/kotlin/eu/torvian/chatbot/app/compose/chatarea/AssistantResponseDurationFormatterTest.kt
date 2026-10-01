package eu.torvian.chatbot.app.compose.chatarea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Checks that an elapsed turn duration renders as a zero-padded `mm:ss` value with unbounded minutes.
 */
class AssistantResponseDurationFormatterTest {

    /** Verifies a fresh turn renders the zero value and that partial seconds are truncated. */
    @Test
    fun formatsZeroAndTruncatesPartialSeconds() {
        assertEquals("00:00", formatAssistantResponseDuration(0.seconds))
        assertEquals("00:00", formatAssistantResponseDuration(999.milliseconds))
        assertEquals("00:01", formatAssistantResponseDuration(1.9.seconds))
    }

    /** Verifies the seconds field keeps two digits up to and across the minute boundary. */
    @Test
    fun padsSecondsUpToTheMinuteBoundary() {
        assertEquals("00:59", formatAssistantResponseDuration(59.seconds))
        assertEquals("01:00", formatAssistantResponseDuration(60.seconds))
        assertEquals("59:59", formatAssistantResponseDuration(3_599.seconds))
    }

    /** Verifies minutes grow past two digits instead of rolling over into an hours field. */
    @Test
    fun keepsCountingInMinutesBeyondAnHour() {
        assertEquals("60:00", formatAssistantResponseDuration(3_600.seconds))
        assertEquals("75:30", formatAssistantResponseDuration(4_530.seconds))
    }

    /** Verifies a negative duration is clamped so it never renders as a negative value. */
    @Test
    fun clampsNegativeDurationToZero() {
        assertEquals("00:00", formatAssistantResponseDuration((-5).seconds))
    }
}
