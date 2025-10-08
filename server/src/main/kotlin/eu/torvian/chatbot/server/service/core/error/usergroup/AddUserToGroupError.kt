package eu.torvian.chatbot.server.service.core.error.usergroup

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when adding a user to a group.
 */
sealed interface AddUserToGroupError {
    /**
     * Group with the specified ID was not found.
     *
     * @property groupId The group ID that was not found
     */
    data class GroupNotFound(val groupId: Long) : AddUserToGroupError

    /**
     * User is already a member of the group.
     *
     * @property userId The user ID
     * @property groupId The group ID
     */
    data class AlreadyMember(val userId: Long, val groupId: Long) : AddUserToGroupError

    /**
     * User or group does not exist (foreign key violation).
     *
     * @property details Additional details about the constraint violation
     */
    data class InvalidRelatedEntity(val details: String) : AddUserToGroupError
}

/**
 * Extension function to convert [AddUserToGroupError] to [ApiError].
 */
fun AddUserToGroupError.toApiError(): ApiError = when (this) {
    is AddUserToGroupError.GroupNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to groupId.toString())

    is AddUserToGroupError.AlreadyMember ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "User is already a member of this group",
            "userId" to userId.toString(), "groupId" to groupId.toString())

    is AddUserToGroupError.InvalidRelatedEntity ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "User or group does not exist: $details")
}
