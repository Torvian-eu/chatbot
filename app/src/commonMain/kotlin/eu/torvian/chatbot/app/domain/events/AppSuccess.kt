package eu.torvian.chatbot.app.domain.events

/**
 * Represents a global success notification.
 *
 * @property message The success message to display. (or string resource ID for localization)
 */
abstract class AppSuccess(
    val message: String
) : AppEvent()