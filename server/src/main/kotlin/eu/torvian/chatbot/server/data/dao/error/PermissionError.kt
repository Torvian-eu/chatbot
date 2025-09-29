package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during Permission data operations.
 */
sealed interface PermissionError {
    /** Indicates that a permission with the specified ID was not found. */
    data class PermissionNotFound(val id: Long) : PermissionError

    /** Indicates that a permission with the specified action-subject pair already exists. */
    data class PermissionAlreadyExists(val action: String, val subject: String) : PermissionError
}

