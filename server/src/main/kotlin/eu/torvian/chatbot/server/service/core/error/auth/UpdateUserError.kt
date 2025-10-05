package eu.torvian.chatbot.server.service.core.error.auth

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur during user profile updates.
 */
sealed interface UpdateUserError {
    /**
     * User with the specified ID was not found.
     *
     * @property userId The ID of the user that was not found
     */
    data class UserNotFound(val userId: Long) : UpdateUserError

    /**
     * Username already exists in the system.
     *
     * @property username The username that already exists
     */
    data class UsernameAlreadyExists(val username: String) : UpdateUserError

    /**
     * Email address already exists in the system.
     *
     * @property email The email that already exists
     */
    data class EmailAlreadyExists(val email: String) : UpdateUserError

    /**
     * Invalid input provided during update.
     *
     * @property reason Description of what input was invalid
     */
    data class InvalidInput(val reason: String) : UpdateUserError

    /**
     * Attempt to modify own status (not allowed).
     *
     * @property userId The ID of the user attempted to be modified
     */
    data class CannotModifyOwnStatus(val userId: Long) : UpdateUserError
}

/**
 * Extension function to convert UpdateUserError to ApiError for HTTP responses.
 */
fun UpdateUserError.toApiError(): ApiError = when (this) {
    is UpdateUserError.UserNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found", "userId" to userId.toString())

    is UpdateUserError.UsernameAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Username already exists", "username" to username)

    is UpdateUserError.EmailAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Email already exists", "email" to email)

    is UpdateUserError.InvalidInput ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, reason)

    is UpdateUserError.CannotModifyOwnStatus ->
        apiError(CommonApiErrorCodes.INVALID_STATE, "Cannot modify own status", "userId" to userId.toString())
}
