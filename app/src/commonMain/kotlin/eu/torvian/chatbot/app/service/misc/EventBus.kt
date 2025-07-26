package eu.torvian.chatbot.app.service.misc

import eu.torvian.chatbot.app.domain.events.AppEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
}