package eu.torvian.chatbot.server.data.dao.error

/**
 * Sealed hierarchy of errors that can occur during built-in worker tool definition DAO operations.
 */
sealed class BuiltInToolDefinitionError {
    /**
     * Error when a built-in worker tool definition is not found.
     *
     * @property toolDefinitionId The base tool definition ID that was not found.
     */
    data class NotFound(
        val toolDefinitionId: Long,
    ) : BuiltInToolDefinitionError()

    /**
     * Error when a base tool definition is already linked to a worker as a built-in tool.
     *
     * @property toolDefinitionId The base tool definition ID that already has a built-in linkage.
     */
    data class DuplicateLinkage(
        val toolDefinitionId: Long,
    ) : BuiltInToolDefinitionError()

    /**
     * Error when either the base tool definition or the worker does not exist, typically caused by
     * a foreign key constraint violation while creating a built-in linkage.
     *
     * @property toolDefinitionId The base tool definition ID being linked.
     * @property workerId The worker ID the tool should be linked to.
     * @property message Description of the foreign key violation.
     */
    data class ReferencedEntityNotFound(
        val toolDefinitionId: Long,
        val workerId: Long,
        val message: String = "Tool definition or worker does not exist",
    ) : BuiltInToolDefinitionError()
}
