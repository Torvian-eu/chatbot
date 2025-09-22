package eu.torvian.chatbot.server.data.dao.error.usergroup

/**
 * Errors that can occur when inserting a new group.
 */
sealed interface InsertGroupError {
    /**
     * A group with the specified name already exists.
     *
     * @property name The name that already exists
     */
    data class GroupNameAlreadyExists(val name: String) : InsertGroupError
}
