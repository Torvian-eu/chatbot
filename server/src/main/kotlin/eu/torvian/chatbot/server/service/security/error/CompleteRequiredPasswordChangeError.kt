package eu.torvian.chatbot.server.service.security.error

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when completing a required password change.
 *
 * This operation is used when a user is forced to change their password (requiresPasswordChange = true)
 * and does not require the current password.
 */
sealed interface CompleteRequiredPasswordChangeError {
    /**
     * The requester is using a restricted (untrusted) session and cannot complete the password change.
     */
    data object InsufficientPermissions : CompleteRequiredPasswordChangeError

    /**
     * Password change is not required for this user.
     * The user must have requiresPasswordChange = true to use this endpoint.
     */
    data object PasswordChangeNotRequired : CompleteRequiredPasswordChangeError

    /**
     * The new password does not meet strength requirements.
     *
     * @property reason Description of why the password is invalid
     */
    data class WeakPassword(val reason: String) : CompleteRequiredPasswordChangeError

    /**
     * User with the specified ID was not found.
     */
    data object UserNotFound : CompleteRequiredPasswordChangeError

    /**
     * Failed to update the user's password in the database.
     *
     * @property reason Description of why the update failed
     */
    data class UpdateFailed(val reason: String) : CompleteRequiredPasswordChangeError
}

/**
 * Extension function to convert CompleteRequiredPasswordChangeError to ApiError for HTTP responses.
 */
fun CompleteRequiredPasswordChangeError.toApiError(): ApiError = when (this) {
    is CompleteRequiredPasswordChangeError.InsufficientPermissions ->
        apiError(
            CommonApiErrorCodes.PERMISSION_DENIED,
            "Action requires a trusted session. Please verify via email or another device."
        )

    is CompleteRequiredPasswordChangeError.PasswordChangeNotRequired ->
        apiError(
            CommonApiErrorCodes.FAILED_PRECONDITION,
            "Password change is not required"
        )

    is CompleteRequiredPasswordChangeError.WeakPassword ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Weak password", "reason" to reason)

    is CompleteRequiredPasswordChangeError.UserNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found")

    is CompleteRequiredPasswordChangeError.UpdateFailed ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to update password", "reason" to reason)
}
