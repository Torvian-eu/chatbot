package eu.torvian.chatbot.app.domain.events

/**
 * Represents a global warning notification.
 *
 * @property message The warning message to display.
 */
abstract class AppWarning(
    val message: String,
) : AppEvent()