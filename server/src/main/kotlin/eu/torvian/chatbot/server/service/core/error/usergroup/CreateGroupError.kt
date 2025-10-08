package eu.torvian.chatbot.server.service.core.error.usergroup

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when creating a group.
 */
sealed interface CreateGroupError {
    /**
     * A group with the specified name already exists.
     *
     * @property name The name that already exists
     */
    data class GroupNameAlreadyExists(val name: String) : CreateGroupError

    /**
     * The provided group name is invalid.
     *
     * @property name The invalid group name
     * @property reason Human-readable explanation of why the name is invalid
     */
    data class InvalidGroupName(val name: String, val reason: String) : CreateGroupError

    /**
     * An unexpected error occurred during group creation.
     *
     * @property message Description of the error
     */
    data class Unexpected(val message: String) : CreateGroupError
}

/**
 * Extension function to convert [CreateGroupError] to [ApiError].
 */
fun CreateGroupError.toApiError(): ApiError = when (this) {
    is CreateGroupError.GroupNameAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Group name already exists", "groupName" to name)

    is CreateGroupError.InvalidGroupName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid group name: $reason", "groupName" to name)

    is CreateGroupError.Unexpected ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to create group: $message")
}
