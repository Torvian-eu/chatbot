package eu.torvian.chatbot.server.service.core.error.usergroup

/**
 * Sealed interface representing errors that can occur when retrieving a group by name.
 */
sealed interface GetGroupByNameError {
    /**
     * Group with the specified name was not found.
     *
     * @property name The name that was not found
     */
    data class NotFound(val name: String) : GetGroupByNameError
}
