package eu.torvian.chatbot.server.service.core.error.group

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors when renaming a chat group.
 */
sealed interface RenameGroupError {
    /**
     * Indicates that the group with the specified ID was not found.
     * Maps from GroupError.GroupNotFound in the DAO layer.
     */
    data class GroupNotFound(val id: Long) : RenameGroupError
    /**
     * Indicates that the provided new name is invalid (e.g., blank).
     */
    data class InvalidName(val reason: String) : RenameGroupError
}

fun RenameGroupError.toApiError(): ApiError = when (this) {
    is RenameGroupError.GroupNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Group not found",
        "groupId" to id.toString()
    )

    is RenameGroupError.InvalidName -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid group name",
        "reason" to reason
    )
}
