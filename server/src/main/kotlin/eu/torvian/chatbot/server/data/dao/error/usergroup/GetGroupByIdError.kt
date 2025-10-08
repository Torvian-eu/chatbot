package eu.torvian.chatbot.server.data.dao.error.usergroup

/**
 * Errors that can occur when getting a group by ID.
 * Shared by functions that need to find a group by ID.
 */
sealed interface GetGroupByIdError {
    /**
     * Group with the specified ID was not found.
     *
     * @property id The ID that was not found
     */
    data class GroupNotFound(val id: Long) : GetGroupByIdError
}
