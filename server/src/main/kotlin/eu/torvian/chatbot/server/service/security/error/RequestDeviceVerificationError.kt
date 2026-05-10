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
}

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
}
