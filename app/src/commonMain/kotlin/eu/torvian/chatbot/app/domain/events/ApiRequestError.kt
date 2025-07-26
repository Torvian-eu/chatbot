package eu.torvian.chatbot.app.domain.events

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_api_request
import eu.torvian.chatbot.common.api.ApiError
import org.jetbrains.compose.resources.getString

/**
 * Represents an error originating from an API request.
 * Includes the specific ApiError for detailed handling.
 *
 * @property apiError The structured API error object
 * @param message A human-readable message for display to the user
 * @param shortMessage A short version of the error message
 * @param isRetryable Whether the error is retryable (defaults to false)
 */
class ApiRequestError(
    val apiError: ApiError,
    message: String,
    shortMessage: String? = null,
    isRetryable: Boolean = false
) : GlobalError(
    message = message,
    shortMessage = shortMessage,
    isRetryable = isRetryable
)

/**
 * Factory function to create an ApiRequestError with a localized message.
 *
 * @param apiError The structured API error object
 * @param shortMessage A short version of the error message
 * @param isRetryable Whether the error is retryable (defaults to false)
 */
suspend fun apiRequestError(
    apiError: ApiError,
    shortMessage: String,
    isRetryable: Boolean = false
): ApiRequestError {
    val message = getString(Res.string.error_api_request,shortMessage, apiError.message)
    return ApiRequestError(apiError, message, shortMessage, isRetryable)
}