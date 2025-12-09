package eu.torvian.chatbot.server.data.dao.error

/**
 * Sealed hierarchy of errors that can occur during LocalMCPToolDefinition DAO operations.
 */
sealed class LocalMCPToolDefinitionError {
    /**
     * Error when a tool definition is not found.
     *
     * @property toolDefinitionId The tool definition ID
     */
    data class NotFound(
        val toolDefinitionId: Long,
    ) : LocalMCPToolDefinitionError()
}

/**
 * Sealed hierarchy of errors specific to insertTool operation.
 */
sealed class InsertToolError : LocalMCPToolDefinitionError() {
    /**
     * Error when a tool definition is already linked to an MCP server.
     *
     * @property toolDefinitionId The tool definition ID
     */
    data class DuplicateLinkage(
        val toolDefinitionId: Long
    ) : InsertToolError()

    /**
     * Error when either the tool definition or MCP server does not exist.
     * This occurs when a foreign key constraint is violated during linkage creation.
     *
     * @property toolDefinitionId The tool definition ID
     * @property mcpServerId The MCP server ID
     * @property message Description of the foreign key violation
     */
    data class ReferencedEntityNotFound(
        val toolDefinitionId: Long,
        val mcpServerId: Long,
        val message: String = "Tool definition or MCP server does not exist"
    ) : InsertToolError()
}

