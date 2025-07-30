package eu.torvian.chatbot.app.domain.events

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Base class for all application events.
 * Provides a unique ID for each event.
 *
 * @property eventId Unique identifier for the event.
 */
@OptIn(ExperimentalUuidApi::class)
abstract class AppEvent(
    open val eventId: String = Uuid.random().toString()
)