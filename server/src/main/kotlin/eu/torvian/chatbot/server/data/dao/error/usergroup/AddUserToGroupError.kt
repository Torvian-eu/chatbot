package eu.torvian.chatbot.server.data.dao.error.usergroup

/**
 * Errors that can occur when adding a user to a group.
 */
sealed interface AddUserToGroupError {
    /**
     * User group membership already exists.
     *
     * @property userId The user ID
     * @property groupId The group ID
     */
    data class MembershipAlreadyExists(val userId: Long, val groupId: Long) : AddUserToGroupError

    /**
     * Foreign key constraint violation (user or group doesn't exist).
     *
     * @property details Additional details about the constraint violation
     */
    data class ForeignKeyViolation(val details: String) : AddUserToGroupError
}
