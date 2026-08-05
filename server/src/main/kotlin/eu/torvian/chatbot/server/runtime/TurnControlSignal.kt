package eu.torvian.chatbot.server.runtime

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents cooperative control requested for one chat turn, including pausing and cancellation.
 *
 * Unlike coroutine cancellation, completing this signal does not abort the surrounding event
 * pipeline. Runtime operations observe it at their natural suspension and iteration boundaries,
 * allowing terminal cancellation events to be emitted normally.
 */
class TurnControlSignal {
    private val deferred = CompletableDeferred<Unit>()

    /** Thread-safe soft-pause state shared by the WebSocket reader and turn worker. */
    private val _isPaused = AtomicBoolean(false)

    /** Indicates whether cancellation has been requested for this turn. */
    val isCancelled: Boolean
        get() = deferred.isCompleted

    /** Indicates whether the current turn must stop before beginning another LLM iteration. */
    val isPaused: Boolean
        get() = _isPaused.get()

    /** Requests a soft pause that leaves the currently active assistant/tool step undisturbed. */
    fun pause() {
        _isPaused.set(true)
    }

    /** Marks this turn as cancelled; repeated requests have no additional effect. */
    fun cancel() {
        deferred.complete(Unit)
    }

    /** Suspends until cancellation has been requested for this turn. */
    suspend fun awaitCancelled() {
        deferred.await()
    }
}
