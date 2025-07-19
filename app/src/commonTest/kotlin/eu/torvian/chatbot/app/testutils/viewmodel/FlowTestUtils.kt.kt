package eu.torvian.chatbot.app.testutils.viewmodel

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

/**
 * Starts collecting a StateFlow in the background using the test dispatcher's scope
 * and adds all emissions to the provided [collected] structure.
 */
fun <T> TestScope.startCollecting(flow: StateFlow<T>, collected: MutableList<T>) {
    backgroundScope.launch {
        flow.collect { value ->
            collected.add(value)
        }
    }
}
