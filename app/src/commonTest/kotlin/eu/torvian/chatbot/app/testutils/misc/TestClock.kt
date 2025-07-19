package eu.torvian.chatbot.app.testutils.misc

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * Test clock implementation for controlling time in tests.
 *
 * @property currentTime The current time as an [Instant].
 */
class TestClock(private var currentTime: Instant) : Clock {
    override fun now(): Instant = currentTime

    fun advanceTime(duration: Duration) {
        currentTime = currentTime.plus(duration)
    }

    fun setTime(newTime: Instant) {
        currentTime = newTime
    }
}