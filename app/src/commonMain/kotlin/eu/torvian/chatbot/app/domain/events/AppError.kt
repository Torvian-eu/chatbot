package eu.torvian.chatbot.app.domain.events

/**
 * A class representing a global application error.
 *
 * @param message The error message to display.
 * @param isRetryable Whether the error is retryable.
 */
abstract class AppError(
    val message: String,
    val isRetryable: Boolean = false
) : AppEvent()
