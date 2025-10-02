package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during Role data operations.
 */
sealed interface RoleError {
    /** Indicates that a role with the specified ID was not found. */
    data class RoleNotFound(val id: Long) : RoleError

    /** Indicates that a role with the specified name was not found. */
    data class RoleNotFoundByName(val name: String) : RoleError

    /** Indicates that a role name is already taken by another role. */
    data class RoleNameAlreadyExists(val name: String) : RoleError
}

