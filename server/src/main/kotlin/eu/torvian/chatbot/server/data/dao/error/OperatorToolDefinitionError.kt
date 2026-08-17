package eu.torvian.chatbot.server.data.dao.error

/**
 * Sealed hierarchy of errors that can occur during operator tool definition DAO operations.
 */
sealed class OperatorToolDefinitionError {

    /**
     * Error when an operator tool definition is not found.
     *
     * @property toolDefinitionId The base tool definition ID that was not found.
     */
    data class NotFound(
        val toolDefinitionId: Long,
    ) : OperatorToolDefinitionError()

    /**
     * Error when a base tool definition is already linked to a user as an operator tool.
     *
     * @property toolDefinitionId The base tool definition ID that already has an operator linkage.
     */
    data class DuplicateLinkage(
        val toolDefinitionId: Long,
    ) : OperatorToolDefinitionError()

    /**
     * Error when either the base tool definition or the user does not exist, typically caused by
     * a foreign key constraint violation while creating an operator linkage.
     *
     * @property toolDefinitionId The base tool definition ID being linked.
     * @property userId The user ID the tool should be linked to.
     * @property message Description of the foreign key violation.
     */
    data class ReferencedEntityNotFound(
        val toolDefinitionId: Long,
        val userId: Long,
        val message: String = "Tool definition or user does not exist",
    ) : OperatorToolDefinitionError()
}
