package eu.torvian.chatbot.app.viewmodel.chat.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Pins the freshness rule of [isToolCallConstructionActive], which is the only place the tool-call construction
 * window is interpreted.
 */
class ToolCallConstructionTest {

    private val start = Instant.fromEpochSeconds(1_000L)

    /** Verifies a message with no recorded delta never shows the indicator. */
    @Test
    fun `no record is inactive`() {
        assertFalse(isToolCallConstructionActive(now = start, lastDeltaAt = null))
    }

    /** Verifies a delta that has just arrived makes the indicator active without any delay. */
    @Test
    fun `a just arrived record is active`() {
        assertTrue(isToolCallConstructionActive(now = start, lastDeltaAt = start))
    }

    /** Verifies a record younger than a full window is still active. */
    @Test
    fun `a record younger than the window is active`() {
        assertTrue(
            isToolCallConstructionActive(
                now = start + TOOL_CALL_CONSTRUCTION_WINDOW - 1.seconds,
                lastDeltaAt = start
            )
        )
    }

    /** Verifies the window bound is exclusive: exactly one window old already counts as stale. */
    @Test
    fun `a record exactly one window old is inactive`() {
        assertFalse(
            isToolCallConstructionActive(
                now = start + TOOL_CALL_CONSTRUCTION_WINDOW,
                lastDeltaAt = start
            )
        )
    }

    /** Verifies a record older than the window is inactive. */
    @Test
    fun `an expired record is inactive`() {
        assertFalse(
            isToolCallConstructionActive(
                now = start + TOOL_CALL_CONSTRUCTION_WINDOW + 1.seconds,
                lastDeltaAt = start
            )
        )
    }

    /** Verifies the window rolls from the latest delta, so a continuing stream keeps the indicator alive. */
    @Test
    fun `a newer record re-activates a message whose earlier record had lapsed`() {
        val later = start + TOOL_CALL_CONSTRUCTION_WINDOW + 10.seconds

        assertFalse(isToolCallConstructionActive(now = later, lastDeltaAt = start))
        assertTrue(isToolCallConstructionActive(now = later, lastDeltaAt = later))
    }
}
