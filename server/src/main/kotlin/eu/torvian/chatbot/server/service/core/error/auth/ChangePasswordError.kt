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

    /**
     * New password is the same as the current password.
     */
    data object SameAsCurrentPassword : ChangePasswordError

    /**
     * The current password provided does not match the stored password hash.
     */
    data object InvalidCurrentPassword : ChangePasswordError

    /**
     * The requester does not have permission to change the password.
     * This is returned for restricted (untrusted) sessions.
     */
    data object InsufficientPermissions : ChangePasswordError
}

/**
 * Extension function to convert ChangePasswordError to ApiError for HTTP responses.
 */
fun ChangePasswordError.toApiError(): ApiError = when (this) {
    is ChangePasswordError.UserNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found", "userId" to userId.toString())

    is ChangePasswordError.InvalidPassword ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, reason)

    is ChangePasswordError.SameAsCurrentPassword ->
        // Use a message that client-side mapping already expects
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Password cannot be reused")

    is ChangePasswordError.InvalidCurrentPassword ->
        apiError(CommonApiErrorCodes.INVALID_CREDENTIALS, "Current password is incorrect")

    is ChangePasswordError.InsufficientPermissions ->
        apiError(
            CommonApiErrorCodes.PERMISSION_DENIED,
            "Action requires a trusted session. Please verify via email or another device."
        )
}
