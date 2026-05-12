package eu.torvian.chatbot.server.service.security.error

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when requesting device verification.
 */
sealed interface RequestDeviceVerificationError {
    /**
     * User has no email address on file, cannot send verification email.
     */
    data object UserHasNoEmail : RequestDeviceVerificationError

    /**
     * Rate limit exceeded - user can only request one verification email per device per hour.
     */
    data class RateLimitExceeded(val retryAfterMillis: Long) : RequestDeviceVerificationError

    /**
     * Failed to send the verification notification.
     *
     * This can be due to email service failure or other notification delivery issues.
     *
     * @property reason A human-readable description of why the notification failed to send.
     */
    data class NotificationServiceFailed(val reason: String) : RequestDeviceVerificationError
}

/**
 * Converts this error to an [ApiError] for HTTP response.
 */
fun RequestDeviceVerificationError.toApiError(): ApiError = when (this) {
    is RequestDeviceVerificationError.UserHasNoEmail ->
        apiError(
            CommonApiErrorCodes.FAILED_PRECONDITION,
            "User has no email address on file. Please add an email to your account first."
        )

    is RequestDeviceVerificationError.RateLimitExceeded ->
        apiError(
            CommonApiErrorCodes.RATE_LIMIT,
            "Verification email already sent. Please check your inbox or try again later.",
            "retryAfterSeconds" to (retryAfterMillis / 1000).toString()
        )

    is RequestDeviceVerificationError.NotificationServiceFailed ->
        apiError(
            CommonApiErrorCodes.INTERNAL,
            "Failed to send verification notification: ${this.reason}"
        )
}

/**
 * Returns additional HTTP headers to include in the error response.
 */
fun RequestDeviceVerificationError.toErrorHeaders(): Map<String, String> = when (this) {
    is RequestDeviceVerificationError.UserHasNoEmail -> emptyMap()

    is RequestDeviceVerificationError.RateLimitExceeded ->
        mapOf("Retry-After" to (retryAfterMillis / 1000).toString())

    is RequestDeviceVerificationError.NotificationServiceFailed -> emptyMap()
}
