package eu.torvian.chatbot.server.data.dao.error

/**
 * Sealed hierarchy of errors that can occur during LocalMCPToolDefinition DAO operations.
 */
sealed class LocalMCPToolDefinitionError {
    /**
     * Error when the requested linkage is not found.
     *
     * @property toolDefinitionId The tool definition ID that was not found
     * @property mcpServerId The MCP server ID (optional, if searching by tool ID only)
     */
    data class NotFound(
        val toolDefinitionId: Long,
        val mcpServerId: Long? = null
    ) : LocalMCPToolDefinitionError()
}

/**
 * Sealed hierarchy of errors specific to createLinkage operation.
 */
sealed class CreateLinkageError {
    /**
     * Error when a linkage already exists between the tool and server.
     *
     * @property toolDefinitionId The tool definition ID
     * @property mcpServerId The MCP server ID
     */
    data class DuplicateLinkage(
        val toolDefinitionId: Long,
        val mcpServerId: Long
    ) : CreateLinkageError()

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
    ) : CreateLinkageError()
}

