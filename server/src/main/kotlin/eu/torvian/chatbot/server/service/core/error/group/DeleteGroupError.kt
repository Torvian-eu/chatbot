package eu.torvian.chatbot.server.service.core.error.group

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors when deleting a chat group.
 */
sealed interface DeleteGroupError {
    /**
     * Indicates that the group with the specified ID was not found.
     * Maps from GroupError.GroupNotFound in the DAO layer.
     */
    data class GroupNotFound(val id: Long) : DeleteGroupError
}

fun DeleteGroupError.toApiError(): ApiError = when (this) {
    is DeleteGroupError.GroupNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Group not found",
        "groupId" to id.toString()
    )
}
