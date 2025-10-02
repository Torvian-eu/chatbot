package eu.torvian.chatbot.server.service.core.error.auth

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur during password changes.
 */
sealed interface ChangePasswordError {
    /**
     * User with the specified ID was not found.
     *
     * @property userId The ID of the user that was not found
     */
    data class UserNotFound(val userId: Long) : ChangePasswordError

    /**
     * Password does not meet strength requirements.
     *
     * @property reason Description of why the password is invalid
     */
    data class InvalidPassword(val reason: String) : ChangePasswordError
}

/**
 * Extension function to convert ChangePasswordError to ApiError for HTTP responses.
 */
fun ChangePasswordError.toApiError(): ApiError = when (this) {
    is ChangePasswordError.UserNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found", "userId" to userId.toString())

    is ChangePasswordError.InvalidPassword ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, reason)
}
