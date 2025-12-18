package eu.torvian.chatbot.app.service.api

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.ApiErrorCode
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.matches

/**
 * Defines a hierarchy of errors that can occur during direct interaction with an external API resource.
 * These errors are produced by API client implementations and represent low-level details
 * such as network failures, structured server responses (including [ApiError] DTOs),
 * and data serialization/deserialization issues.
 */
sealed class ApiResourceError {
    /**
     * A comprehensive, technical message describing the error, suitable for logging and debugging.
     * This message often includes details from the underlying [cause] if available.
     */
    abstract val message: String

    /**
     * The underlying [Throwable] that caused this error, if applicable.
     */
    abstract val cause: Throwable?

    /**
     * Represents an error that occurred due to network connectivity problems
     * when trying to reach an API endpoint.
     * Examples include no internet connection, request timeouts, or host unreachable.
     *
     * @property description A brief explanation of the network issue.
     * @property cause The underlying [Throwable] (e.g., [kotlinx.io.IOException]).
     */
    data class NetworkError(
        val description: String,
        override val cause: Throwable?
    ) : ApiResourceError() {
        override val message: String get() = "Network Error: $description" + (cause?.message?.let { " - Caused by: $it" } ?: "")

        companion object {
            /**
             * Creates a [NetworkError] from a [Throwable], extracting its message for the description.
             *
             * @param throwable The original network exception.
             * @return A [NetworkError] instance.
             */
            fun from(throwable: Throwable): NetworkError {
                return NetworkError(throwable.message ?: "An unknown network error occurred.", throwable)
            }
        }
    }

    /**
     * Represents a structured error response received directly from the backend API.
     * This usually corresponds to non-2xx HTTP responses where the server provides
     * a specific error payload.
     *
     * @property apiError The original [ApiError] DTO deserialized from the server's response.
     * @property cause Typically `null` for server-provided errors, as the [apiError]
     *                 already contains the detailed server-side reason.
     */
    data class ServerError(
        val apiError: ApiError,
        override val cause: Throwable? = null
    ) : ApiResourceError() {
        override val message: String get() = "Server Error (Code: ${apiError.code}): ${apiError.message}"
    }

    /**
     * Represents an error that occurred during the serialization of an outgoing request
     * or the deserialization of an incoming API response.
     * Examples include malformed JSON or type mismatches.
     *
     * @property description A brief explanation of the serialization/deserialization failure.
     * @property cause The underlying [Throwable] (e.g., [kotlinx.serialization.SerializationException]).
     */
    data class SerializationError(
        val description: String,
        override val cause: Throwable?
    ) : ApiResourceError() {
        override val message: String get() = "Serialization Error: $description" + (cause?.message?.let { " - Caused by: $it" } ?: "")

        companion object {
            /**
             * Creates a [SerializationError] from a [Throwable].
             *
             * @param throwable The original serialization exception.
             * @return A [SerializationError] instance.
             */
            fun from(throwable: Throwable): SerializationError {
                return SerializationError("Failed to parse API response", throwable)
            }
        }
    }

    /**
     * Represents any other unexpected or unhandled error that occurred at the API client level.
     *
     * @property description A brief explanation of the unknown error.
     * @property cause The underlying [Throwable] if available.
     */
    data class UnknownError(
        val description: String,
        override val cause: Throwable?
    ) : ApiResourceError() {
        override val message: String get() = "Unknown API Error: $description" + (cause?.message?.let { " - Caused by: $it" } ?: "")

        companion object {
            /**
             * Creates an [UnknownError] from a [Throwable], extracting its message for the description.
             *
             * @param throwable The original unexpected exception.
             * @return An [UnknownError] instance.
             */
            fun from(throwable: Throwable): UnknownError {
                return UnknownError(throwable.message ?: "An unexpected error occurred.", throwable)
            }
        }
    }
}

/**
 * Converts a common API [ApiError] DTO (typically from a server response body)
 * into an [ApiResourceError.ServerError].
 *
 * @receiver The [ApiError] to convert.
 * @return An [ApiResourceError.ServerError] instance.
 */
fun ApiError.toApiResourceError(): ApiResourceError.ServerError = ApiResourceError.ServerError(this)


// --- Convenience extensions on ApiResourceError ---

/**
 * Returns true when this [ApiResourceError] wraps a server-provided [ApiError]
 * that matches the given [apiErrorCode].
 */
fun ApiResourceError.matches(apiErrorCode: ApiErrorCode): Boolean =
    this is ApiResourceError.ServerError && this.apiError.matches(apiErrorCode)

/**
 * Convenience check for a NOT_FOUND server error.
 */
fun ApiResourceError.isNotFound(): Boolean = matches(CommonApiErrorCodes.NOT_FOUND)
