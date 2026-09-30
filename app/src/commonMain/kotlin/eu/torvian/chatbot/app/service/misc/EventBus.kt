package eu.torvian.chatbot.app.service.misc

import arrow.core.Either
import eu.torvian.chatbot.app.domain.events.AppEvent
import eu.torvian.chatbot.app.domain.events.TimeoutError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * A generic EventBus for broadcasting application-wide, transient events.
 * It uses a SharedFlow to allow multiple collectors.
 *
 * Every event type shares the one flow; consumers select what they care about (`AppShell` shows
 * snackbars only for the user-facing error/warning/success events, while other consumers filter for
 * their own types), so a new event type needs no change to existing consumers.
 */
class EventBus {

    companion object {
        /**
         * Slots a publisher may fill while collectors are still busy with earlier events.
         *
         * A bufferless shared flow rejects a value whenever any subscriber is not ready to receive
         * it, so without this the non-suspending [tryEmitEvent] would lose events as soon as one of
         * the several subscribers is mid-processing. Overflow is left to suspend rather than being
         * discarded so [emitEvent] callers keep their existing "never silently lost" guarantee.
         *
         * Visible to the module's tests so a probe that must overflow the buffer can be sized from
         * it, rather than from a number that stops proving anything once the depth grows.
         */
        internal const val EVENT_BUFFER_CAPACITY = 32
    }

    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER_CAPACITY
    )

    /** Stream of every application event; consumers filter for the types they handle. */
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    /**
     * Emits a new application event.
     *
     * Returns once the event is handed over, which on a shared flow with buffer capacity may be before
     * any collector has observed it: the call suspends only while the buffer is full, i.e. while
     * collectors have not caught up with a burst of more than [EVENT_BUFFER_CAPACITY] unprocessed
     * events. It is therefore not a barrier that a caller can use to know its subscribers have run,
     * and the event is never dropped.
     *
     * @param event The event to emit.
     */
    suspend fun emitEvent(event: AppEvent) {
        _events.emit(event)
    }

    /**
     * Emits a new application event without suspending.
     *
     * Intended for publishers that run where suspending is impossible, such as a `finally` block of
     * an already-cancelled coroutine. When nobody collects, the event is discarded: there is no
     * replay, so a stale event can never be delivered later.
     *
     * @param event The event to emit.
     * @return `true` when the event was accepted for delivery, `false` when the buffer was full and
     *         the event was dropped.
     */
    fun tryEmitEvent(event: AppEvent): Boolean = _events.tryEmit(event)

    /**
     * Suspends until the first event of type [T] matching the predicate is received.
     *
     * This is useful for coordinating workflows that need to wait for specific events,
     * such as waiting for a session to load before continuing navigation.
     *
     * @param timeout The maximum time to wait for the event, or null for no timeout.
     * @param predicate Optional predicate to filter events. Defaults to accepting all events.
     * @return Either a [TimeoutError] if the timeout was exceeded, or the matching event.
     */
    suspend inline fun <reified T : AppEvent> awaitFirst(
        timeout: Duration? = null,
        noinline predicate: (T) -> Boolean = { true }
    ): Either<TimeoutError, T> {
        return if (timeout != null) {
            try {
                Either.Right(withTimeout(timeout) {
                    events.filterIsInstance<T>().first { predicate(it) }
                })
            } catch (_: TimeoutCancellationException) {
                Either.Left(TimeoutError)
            }
        } else {
            Either.Right(events.filterIsInstance<T>().first { predicate(it) })
        }
    }
}
