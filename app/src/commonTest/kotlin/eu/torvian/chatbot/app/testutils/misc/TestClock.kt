package eu.torvian.chatbot.app.testutils.misc

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

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