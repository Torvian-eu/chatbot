package eu.torvian.chatbot.server.service.core.error.usergroup

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Extension functions to convert user group service errors to API errors.
 */

fun GetGroupByIdError.toApiError(): ApiError = when (this) {
    is GetGroupByIdError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User group not found", "groupId" to id.toString())
}

fun GetGroupByNameError.toApiError(): ApiError = when (this) {
    is GetGroupByNameError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User group not found", "groupName" to name)
}

fun CreateGroupError.toApiError(): ApiError = when (this) {
    is CreateGroupError.GroupNameAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "User group name already exists", "groupName" to name)

    is CreateGroupError.InvalidGroupName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid group name: $reason", "groupName" to name)

    is CreateGroupError.Unexpected ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to create group: $message")
}

fun UpdateGroupError.toApiError(): ApiError = when (this) {
    is UpdateGroupError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User group not found", "groupId" to id.toString())

    is UpdateGroupError.GroupNameAlreadyExists ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "User group name already exists", "groupName" to name)

    is UpdateGroupError.InvalidOperation ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Invalid operation: $reason")
}

fun DeleteGroupError.toApiError(): ApiError = when (this) {
    is DeleteGroupError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User group not found", "groupId" to id.toString())

    is DeleteGroupError.InvalidOperation ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Invalid operation: $reason")
}

fun AddUserToGroupError.toApiError(): ApiError = when (this) {
    is AddUserToGroupError.GroupNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to groupId.toString())

    is AddUserToGroupError.AlreadyMember ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "User is already a member of this group",
            "userId" to userId.toString(), "groupId" to groupId.toString())

    is AddUserToGroupError.InvalidRelatedEntity ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "User or group does not exist: $details")
}

fun RemoveUserFromGroupError.toApiError(): ApiError = when (this) {
    is RemoveUserFromGroupError.GroupNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User group not found", "groupId" to groupId.toString())

    is RemoveUserFromGroupError.NotMember ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "User is not a member of this group", "userId" to userId.toString(), "groupId" to groupId.toString())

    is RemoveUserFromGroupError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Membership not found", "userId" to userId.toString(), "groupId" to groupId.toString())

    is RemoveUserFromGroupError.InvalidOperation ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Invalid operation: $reason")
}
