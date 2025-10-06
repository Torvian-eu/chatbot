package eu.torvian.chatbot.server.service.core.error.usergroup

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when removing a user from a group.
 */
sealed interface RemoveUserFromGroupError {
    /**
     * Group with the specified ID was not found.
     *
     * @property groupId The group ID that was not found
     */
    data class GroupNotFound(val groupId: Long) : RemoveUserFromGroupError

    /**
     * User is not a member of the group.
     *
     * @property userId The user ID
     * @property groupId The group ID
     */
    data class NotMember(val userId: Long, val groupId: Long) : RemoveUserFromGroupError

    /**
     * Membership record not found in database.
     *
     * @property userId The user ID
     * @property groupId The group ID
     */
    data class NotFound(val userId: Long, val groupId: Long) : RemoveUserFromGroupError

    /**
     * The operation is not allowed (e.g., trying to remove user from a protected group).
     *
     * @property reason Human-readable explanation of why the operation is invalid
     */
    data class InvalidOperation(val reason: String) : RemoveUserFromGroupError
}

/**
 * Extension function to convert [RemoveUserFromGroupError] to [ApiError].
 */
fun RemoveUserFromGroupError.toApiError(): ApiError = when (this) {
    is RemoveUserFromGroupError.GroupNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to groupId.toString())

    is RemoveUserFromGroupError.NotMember ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User is not a member of this group", "userId" to userId.toString(), "groupId" to groupId.toString())

    is RemoveUserFromGroupError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Membership not found", "userId" to userId.toString(), "groupId" to groupId.toString())

    is RemoveUserFromGroupError.InvalidOperation ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Invalid operation: $reason")
}
