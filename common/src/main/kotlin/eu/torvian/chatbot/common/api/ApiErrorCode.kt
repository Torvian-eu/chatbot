package eu.torvian.chatbot.common.api

/**
 * Represents a machine-readable error code for API responses,
 * coupled with its appropriate HTTP status code.
 *
 * @property code The machine-readable error code.
 * @property statusCode The HTTP status code associated with this error.
 */
data class ApiErrorCode(
    val code: String,
    val statusCode: Int
)
