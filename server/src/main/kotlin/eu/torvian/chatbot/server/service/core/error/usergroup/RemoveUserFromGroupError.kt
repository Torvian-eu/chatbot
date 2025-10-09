package eu.torvian.chatbot.server.service.core.error.usergroup

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
