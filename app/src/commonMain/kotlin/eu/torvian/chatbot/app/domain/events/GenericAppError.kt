package eu.torvian.chatbot.app.domain.events

/**
 * Represents a generic, uncategorized application error.
 * By default, not retryable unless specifically decided.
 *
 * @param message A human-readable message describing the error
 * @param isRetryable Whether this error represents a retryable failure
 */
class GenericAppError(
    message: String,
    isRetryable: Boolean = false
) : AppError(
    message = message,
    isRetryable = isRetryable
)