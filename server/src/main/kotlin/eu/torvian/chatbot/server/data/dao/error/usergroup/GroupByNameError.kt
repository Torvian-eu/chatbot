package eu.torvian.chatbot.server.data.dao.error.usergroup

/**
 * Errors that can occur when getting a group by name.
 */
sealed interface GroupByNameError {
    /**
     * Group with the specified name was not found.
     *
     * @property name The name that was not found
     */
    data class GroupNotFoundByName(val name: String) : GroupByNameError
}
