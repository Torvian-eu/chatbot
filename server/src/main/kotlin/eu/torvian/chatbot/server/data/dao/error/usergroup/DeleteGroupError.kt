package eu.torvian.chatbot.server.data.dao.error.usergroup

/**
 * Errors that can occur when deleting a group.
 * This can be shared with other functions that need to find and delete a group.
 */
sealed interface DeleteGroupError {
    /**
     * Group with the specified ID was not found.
     *
     * @property id The ID that was not found
     */
    data class GroupNotFound(val id: Long) : DeleteGroupError
}
