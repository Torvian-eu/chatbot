package eu.torvian.chatbot.app.domain.events

/**
 * A class representing a global application error.
 *
 * @param message The error message to display.
 * @param shortMessage A short version of the error message.
 * @param isRetryable Whether the error is retryable.
 */
abstract class AppError(
    val message: String,
    val shortMessage: String? = null,
    val isRetryable: Boolean = false
) : AppEvent()
