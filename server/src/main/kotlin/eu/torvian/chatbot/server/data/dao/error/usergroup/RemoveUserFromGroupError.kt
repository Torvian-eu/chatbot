package eu.torvian.chatbot.server.data.dao.error.usergroup

/**
 * Errors that can occur when removing a user from a group.
 */
sealed interface RemoveUserFromGroupError {
    /**
     * User group membership was not found.
     *
     * @property userId The user ID
     * @property groupId The group ID
     */
    data class MembershipNotFound(val userId: Long, val groupId: Long) : RemoveUserFromGroupError
}
