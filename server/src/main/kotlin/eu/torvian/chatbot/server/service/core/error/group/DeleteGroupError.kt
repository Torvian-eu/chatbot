package eu.torvian.chatbot.server.service.core.error.group

/**
 * Represents possible errors when deleting a chat group.
 */
sealed interface DeleteGroupError {
    /**
     * Indicates that the group with the specified ID was not found.
     * Maps from GroupError.GroupNotFound in the DAO layer.
     */
    data class GroupNotFound(val id: Long) : DeleteGroupError

    /**
     * Indicates that the user does not have access to delete the requested group.
     */
    data class AccessDenied(val reason: String) : DeleteGroupError
}
