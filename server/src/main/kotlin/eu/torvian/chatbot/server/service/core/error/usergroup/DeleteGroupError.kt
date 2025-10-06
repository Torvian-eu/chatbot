package eu.torvian.chatbot.server.service.core.error.usergroup

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when deleting a group.
 */
sealed interface DeleteGroupError {
    /**
     * Group with the specified ID was not found.
     *
     * @property id The ID that was not found
     */
    data class NotFound(val id: Long) : DeleteGroupError

    /**
     * The operation is not allowed (e.g., trying to delete a protected group).
     *
     * @property reason Human-readable explanation of why the operation is invalid
     */
    data class InvalidOperation(val reason: String) : DeleteGroupError
}

/**
 * Extension function to convert [DeleteGroupError] to [ApiError].
 */
fun DeleteGroupError.toApiError(): ApiError = when (this) {
    is DeleteGroupError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to id.toString())

    is DeleteGroupError.InvalidOperation ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Invalid operation: $reason")
}
