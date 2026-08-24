package eu.torvian.chatbot.server.data.dao.error

/**
 * Sealed hierarchy of errors that can occur during server built-in tool definition DAO operations.
 */
sealed class ServerBuiltInToolDefinitionError {

    /**
     * Error when a server built-in tool definition is not found.
     *
     * @property toolDefinitionId The base tool definition ID that was not found.
     */
    data class NotFound(
        val toolDefinitionId: Long,
    ) : ServerBuiltInToolDefinitionError()

    /**
     * Error when a base tool definition is already linked to a user as a server built-in tool.
     *
     * @property toolDefinitionId The base tool definition ID that already has a server built-in linkage.
     */
    data class DuplicateLinkage(
        val toolDefinitionId: Long,
    ) : ServerBuiltInToolDefinitionError()

    /**
     * Error when either the base tool definition or the user does not exist, typically caused by
     * a foreign key constraint violation while creating a server built-in linkage.
     *
     * @property toolDefinitionId The base tool definition ID being linked.
     * @property userId The user ID the tool should be linked to.
     * @property message Description of the foreign key violation.
     */
    data class ReferencedEntityNotFound(
        val toolDefinitionId: Long,
        val userId: Long,
        val message: String = "Tool definition or user does not exist",
    ) : ServerBuiltInToolDefinitionError()
}
