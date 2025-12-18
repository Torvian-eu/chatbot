package eu.torvian.chatbot.common.api

import kotlinx.serialization.Serializable

/**
 * Represents a structured error response returned by the API.
 *
 * This model provides a machine-readable and human-readable structure for errors,
 * allowing clients to handle specific error scenarios programmatically.
 *
 * Follows patterns used by Firebase, Google APIs, and Stripe.
 *
 * @property statusCode The HTTP status code associated with this error (as an Int).
 * @property code A machine-readable error code string (e.g., "invalid-argument").
 *                This is intended to be parsed and handled by the client application.
 * @property message A human-readable explanation of the error, intended to be displayed to users or logged.
 * @property details Optional additional information about the error (e.g. which field failed, what value was expected).
 */
@Serializable
data class ApiError(
    val statusCode: Int,
    val code: String,
    val message: String,
    val details: Map<String, String>? = null
)

/**
 * Helper function to quickly construct an [ApiError] using an [ApiErrorCode] and named detail pairs.
 *
 * This can be used inline in route handlers for concise error construction.
 *
 * @param apiCode The [ApiErrorCode] instance containing the code string and status Int.
 * @param message A human-readable description of the error.
 * @param details Optional key-value pairs for additional debugging or contextual information.
 * @return A fully constructed [ApiError] instance.
 */
fun apiError(
    apiCode: ApiErrorCode,
    message: String,
    vararg details: Pair<String, String>
): ApiError = ApiError(
    statusCode = apiCode.statusCode,
    code = apiCode.code,
    message = message,
    details = if (details.isNotEmpty()) details.toMap() else null
)

/**
 * Helper function to construct an [ApiError] using a raw status code and string code.
 *
 * Use the ApiErrorCode overload preferably, but this provides flexibility.
 *
 * @param statusCode The HTTP status code (as an Int).
 * @param code The machine-readable error code string.
 * @param message A human-readable description of the error.
 * @param details Optional key-value map for additional context or debug info.
 * @return A structured [ApiError] instance.
 */
fun apiError(
    statusCode: Int,
    code: String,
    message: String,
    vararg details: Pair<String, String>
): ApiError = ApiError(statusCode, code, message, if (details.isNotEmpty()) details.toMap() else null)


// --- Convenience extensions on ApiError ---

/**
 * Returns true if this error's status code and machine-readable code equals the provided [ApiErrorCode].
 */
fun ApiError.matches(apiErrorCode: ApiErrorCode): Boolean =
    this.statusCode == apiErrorCode.statusCode && this.code == apiErrorCode.code
