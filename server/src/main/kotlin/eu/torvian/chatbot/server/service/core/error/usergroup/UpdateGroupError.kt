package eu.torvian.chatbot.server.service.core.error.usergroup

/**
 * Sealed interface representing errors that can occur when updating a group.
 */
sealed interface UpdateGroupError {
    /**
     * Group with the specified ID was not found.
     *
     * @property id The ID that was not found
     */
    data class NotFound(val id: Long) : UpdateGroupError

    /**
     * A group with the specified name already exists.
     *
     * @property name The name that already exists
     */
    data class GroupNameAlreadyExists(val name: String) : UpdateGroupError

    /**
     * The operation is not allowed (e.g., trying to rename a protected group).
     *
     * @property reason Human-readable explanation of why the operation is invalid
     */
    data class InvalidOperation(val reason: String) : UpdateGroupError
}
