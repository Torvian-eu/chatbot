package eu.torvian.chatbot.app.repository

import eu.torvian.chatbot.app.service.api.ApiResourceError

/**
 * Defines a hierarchy of errors that can originate from the Repository layer.
 * These errors represent the outcome of high-level data operations, wrapping
 * lower-level [ApiResourceError]s and adding operational context.
 */
sealed class RepositoryError {
    /**
     * A comprehensive, technical message describing the error, composed by the repository.
     * This message combines high-level operational context with low-level details,
     * making it suitable for direct display in the UI (e.g., in a Snackbar or error dialog).
     */
    abstract val message: String

    /**
     * The underlying [Throwable] that caused this error, typically delegated from
     * the wrapped [ApiResourceError].
     */
    abstract val cause: Throwable?

    /**
     * Represents an error encountered while fetching or manipulating data from an
     * external resource (e.g., an API). This error type adds high-level context
     * to a lower-level [ApiResourceError].
     *
     * @property apiResourceError The detailed, low-level error that occurred at the API client layer.
     * @property contextMessage An optional, high-level message from the repository providing
     *                          context about the specific operation that failed (e.g., "Failed to delete model").
     */
    data class DataFetchError(
        val apiResourceError: ApiResourceError,
        val contextMessage: String? = null
    ) : RepositoryError() {
        override val message: String
            get() = if (contextMessage != null) {
                "$contextMessage: ${apiResourceError.message}"
            } else {
                apiResourceError.message
            }

        override val cause: Throwable?
            get() = apiResourceError.cause
    }

    /**
     * Represents any other unexpected or unhandled error that occurred within the
     * Repository layer itself, not directly tied to a data fetch operation.
     *
     * @property description A brief explanation of the unknown repository error.
     * @property cause The underlying [Throwable] if available.
     */
    data class OtherError(
        val description: String,
        override val cause: Throwable? = null
    ) : RepositoryError() {
        override val message: String
            get() = "Unexpected Repository Error: $description" + (cause?.message?.let { " - Caused by: $it" } ?: "")

        companion object {
            /**
             * Creates an [OtherError] from a [Throwable], extracting its message for the description.
             *
             * @param throwable The original unexpected exception.
             * @return An [OtherError] instance.
             */
            fun from(throwable: Throwable): OtherError {
                return OtherError("An unexpected repository error occurred.", throwable)
            }
        }
    }
}

/**
 * Convenience function to convert an [ApiResourceError] into a [RepositoryError.DataFetchError].
 * This helper is used by repository implementations to wrap API-level errors
 * with optional operational context.
 *
 * @receiver The [ApiResourceError] to convert.
 * @param contextMessage An optional, high-level message providing context about the operation.
 * @return A [RepositoryError.DataFetchError] instance.
 */
fun ApiResourceError.toRepositoryError(
    contextMessage: String? = null
): RepositoryError.DataFetchError {
    return RepositoryError.DataFetchError(this, contextMessage)
}