package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during LocalMCPServer
 * data operations on the server side.
 */
sealed interface LocalMCPServerError {
    /**
     * Indicates that a LocalMCPServer with the specified ID was not found.
     *
     * @param id The ID that was not found
     */
    data class NotFound(val id: Long) : LocalMCPServerError

    /**
     * Indicates that the operation failed due to a foreign key constraint.
     * This typically occurs when trying to delete a server that has associated
     * tool definitions.
     *
     * @param message Detailed error message
     * @param cause The underlying exception that caused this error
     */
    data class ForeignKeyViolation(
        val message: String,
        val cause: Throwable? = null
    ) : LocalMCPServerError

    /**
     * Indicates that the user does not have permission to perform the operation.
     *
     * @param userId The user ID attempting the operation
     * @param serverId The server ID being accessed
     */
    data class Unauthorized(
        val userId: Long,
        val serverId: Long
    ) : LocalMCPServerError
}

/**
 * Error type for LocalMCPServer deletion operations.
 */
sealed interface DeleteLocalMCPServerError {
    /**
     * The server with the specified ID was not found.
     */
    data class NotFound(val id: Long) : DeleteLocalMCPServerError
}

