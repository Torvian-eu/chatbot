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
 */
class EventBus {
    private val _events = MutableSharedFlow<AppEvent>()
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    /**
     * Emits a new application event.
     * @param event The event to emit.
     */
    suspend fun emitEvent(event: AppEvent) {
        _events.emit(event)
    }

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