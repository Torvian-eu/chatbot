package eu.torvian.chatbot.server.service.core.error.auth

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur during email changes.
 */
sealed interface ChangeEmailError {
    /**
     * User with the specified ID was not found.
     *
     * @property userId The ID of the user that was not found
     */
    data class UserNotFound(val userId: Long) : ChangeEmailError

    /**
     * The current password provided does not match the stored password hash.
     */
    data object InvalidCurrentPassword : ChangeEmailError

    /**
     * The new email address is already in use by another user.
     *
     * @property email The email address that is already in use
     */
    data class EmailAlreadyExists(val email: String) : ChangeEmailError

    /**
     * The new email address format is invalid.
     *
     * @property reason Description of why the email is invalid
     */
    data class InvalidEmailFormat(val reason: String) : ChangeEmailError

    /**
     * The requester does not have permission to change the email.
     * This is returned for restricted (untrusted) sessions.
     */
    data object InsufficientPermissions : ChangeEmailError
}

/**
 * Extension function to convert ChangeEmailError to ApiError for HTTP responses.
 */
fun ChangeEmailError.toApiError(): ApiError = when (this) {
    is ChangeEmailError.UserNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found", "userId" to userId.toString())

    is ChangeEmailError.InvalidCurrentPassword ->
        apiError(CommonApiErrorCodes.INVALID_CREDENTIALS, "Current password is incorrect")

    is ChangeEmailError.EmailAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Email already exists", "email" to email)

    is ChangeEmailError.InvalidEmailFormat ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid email format", "reason" to reason)

    is ChangeEmailError.InsufficientPermissions ->
        apiError(
            CommonApiErrorCodes.PERMISSION_DENIED,
            "Action requires a trusted session. Please verify via email or another device."
        )
}
