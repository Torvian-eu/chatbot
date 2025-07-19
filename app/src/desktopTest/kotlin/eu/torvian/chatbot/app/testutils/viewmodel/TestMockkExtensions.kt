package eu.torvian.chatbot.app.testutils.viewmodel

import io.mockk.MockKAdditionalAnswerScope
import io.mockk.MockKStubScope
import kotlinx.coroutines.delay

/**
 * Mocks a suspending function to return a value after a specified virtual delay.
 * Useful in tests with TestCoroutineScheduler to simulate asynchronous processing time.
 *
 * @param returnValue The value to return.
 * @param delayMillis The virtual time to delay in milliseconds. Defaults to 100L.
 */
fun <T, B> MockKStubScope<T, B>.returnsDelayed(
    returnValue: T,
    delayMillis: Long = 100L
): MockKAdditionalAnswerScope<T, B> {
    return coAnswers {
        delay(delayMillis)
        returnValue
    }
}