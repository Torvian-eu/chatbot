package eu.torvian.chatbot.server.service.core.error.mcp

/**
 * Base interface for all Local MCP Server related service errors.
 */
sealed interface LocalMCPServerServiceError

/**
 * Errors that can occur when deleting a server ID.
 */
sealed interface DeleteServerError : LocalMCPServerServiceError {
    /**
     * The requested server was not found.
     * @property id The ID of the server that was not found.
     */
    data class ServerNotFound(val id: Long) : DeleteServerError
}

/**
 * Errors that can occur when validating ownership of a server.
 */
sealed interface ValidateOwnershipError : LocalMCPServerServiceError {
    /**
     * The user is not authorized to access the server.
     */
    data class Unauthorized(val userId: Long, val serverId: Long) : ValidateOwnershipError
}
