package eu.torvian.chatbot.server.service.core.error.usergroup

/**
 * Sealed interface representing errors that can occur when creating a group.
 */
sealed interface CreateGroupError {
    /**
     * A group with the specified name already exists.
     *
     * @property name The name that already exists
     */
    data class GroupNameAlreadyExists(val name: String) : CreateGroupError

    /**
     * The provided group name is invalid.
     *
     * @property name The invalid group name
     * @property reason Human-readable explanation of why the name is invalid
     */
    data class InvalidGroupName(val name: String, val reason: String) : CreateGroupError

    /**
     * An unexpected error occurred during group creation.
     *
     * @property message Description of the error
     */
    data class Unexpected(val message: String) : CreateGroupError
}
