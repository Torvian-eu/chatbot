package eu.torvian.chatbot.app.domain.events

/**
 * Represents an internal event that is not meant to be displayed to the user.
 * This can be used for internal state changes or other non-user-facing events.
 */
abstract class InternalEvent: AppEvent()