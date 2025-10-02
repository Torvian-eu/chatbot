package eu.torvian.chatbot.server.service.core.error.auth

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur during user deletion.
 */
sealed interface DeleteUserError {
    /**
     * User with the specified ID was not found.
     *
     * @property userId The ID of the user that was not found
     */
    data class UserNotFound(val userId: Long) : DeleteUserError

    /**
     * Cannot delete the last administrator in the system.
     *
     * @property userId The ID of the last admin user
     */
    data class CannotDeleteLastAdmin(val userId: Long) : DeleteUserError
}

/**
 * Extension function to convert DeleteUserError to ApiError for HTTP responses.
 */
fun DeleteUserError.toApiError(): ApiError = when (this) {
    is DeleteUserError.UserNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found", "userId" to userId.toString())

    is DeleteUserError.CannotDeleteLastAdmin ->
        apiError(
            CommonApiErrorCodes.CONFLICT,
            "Cannot delete the last administrator",
            "userId" to userId.toString()
        )
}
