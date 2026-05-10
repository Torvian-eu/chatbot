package eu.torvian.chatbot.server.service.security.error

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when verifying a device via token.
 */
sealed interface VerifyDeviceError {
    /**
     * The token is invalid, expired, or already used.
     */
    data object InvalidOrExpiredToken : VerifyDeviceError
}

fun VerifyDeviceError.toApiError(): ApiError = when (this) {
    is VerifyDeviceError.InvalidOrExpiredToken ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Invalid or expired verification link. Please request a new verification email."
        )
}
