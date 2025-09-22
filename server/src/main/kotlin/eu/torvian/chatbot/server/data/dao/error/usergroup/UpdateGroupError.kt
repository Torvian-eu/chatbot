package eu.torvian.chatbot.server.data.dao.error.usergroup

/**
 * Errors that can occur when updating a group.
 * Combines group lookup and name uniqueness validation errors.
 */
sealed interface UpdateGroupError {
    /**
     * Group with the specified ID was not found.
     *
     * @property id The ID that was not found
     */
    data class GroupNotFound(val id: Long) : UpdateGroupError

    /**
     * A group with the specified name already exists.
     *
     * @property name The name that already exists
     */
    data class GroupNameAlreadyExists(val name: String) : UpdateGroupError
}
