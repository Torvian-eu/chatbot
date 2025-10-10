package eu.torvian.chatbot.server.service.core.error.usergroup

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
