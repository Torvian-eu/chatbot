package eu.torvian.chatbot.server.service.core.error.usergroup

/**
 * Sealed interface representing errors that can occur when retrieving a group by ID.
 */
sealed interface GetGroupByIdError {
    /**
     * Group with the specified ID was not found.
     *
     * @property id The ID that was not found
     */
    data class NotFound(val id: Long) : GetGroupByIdError
}
