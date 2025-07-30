package eu.torvian.chatbot.app.domain.events

/**
 * Represents a generic, uncategorized application error.
 * By default, not retryable unless specifically decided.
 *
 * @property originalThrowable The original exception that caused this error, if available
 * @property message A human-readable message describing the error
 * @property isRetryable Whether this error represents a retryable failure
 */
class GenericAppError(
    val originalThrowable: Throwable? = null,
    message: String,
    isRetryable: Boolean = false
) : GlobalError(
    message = message,
    isRetryable = isRetryable
)