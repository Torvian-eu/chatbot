package eu.torvian.chatbot.server.service.core.error.usergroup

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when updating a group.
 */
sealed interface UpdateGroupError {
    /**
     * Group with the specified ID was not found.
     *
     * @property id The ID that was not found
     */
    data class NotFound(val id: Long) : UpdateGroupError

    /**
     * A group with the specified name already exists.
     *
     * @property name The name that already exists
     */
    data class GroupNameAlreadyExists(val name: String) : UpdateGroupError

    /**
     * The operation is not allowed (e.g., trying to rename a protected group).
     *
     * @property reason Human-readable explanation of why the operation is invalid
     */
    data class InvalidOperation(val reason: String) : UpdateGroupError
}

/**
 * Extension function to convert [UpdateGroupError] to [ApiError].
 */
fun UpdateGroupError.toApiError(): ApiError = when (this) {
    is UpdateGroupError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to id.toString())

    is UpdateGroupError.GroupNameAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Group name already exists", "groupName" to name)

    is UpdateGroupError.InvalidOperation ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Invalid operation: $reason")
}
