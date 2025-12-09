package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.server.service.core.error.mcp.DeleteServerError
import eu.torvian.chatbot.server.service.core.error.mcp.ValidateOwnershipError

/**
 * Service interface for managing Local MCP Server IDs.
 *
 * This service handles server-side operations for Local MCP Servers, which includes
 * ID generation and ownership tracking. Full MCP server configurations are stored
 * client-side.
 *
 * Note: This service only manages server IDs, not full configurations.
 */
interface LocalMCPServerService {
    /**
     * Generates a new unique ID for a Local MCP Server.
     *
     * The server generates and stores only the ID and userId for ownership tracking.
     * The client will store the full configuration locally using this ID.
     *
     * @param userId The ID of the user who owns this MCP server configuration
     * @return A generated server ID
     */
    suspend fun generateServerId(userId: Long): Long

    /**
     * Retrieves all server IDs owned by a specific user.
     *
     * @param userId The ID of the user
     * @return A list of server IDs
     */
    suspend fun getServerIdsByUserId(userId: Long): List<Long>

    /**
     * Deletes a LocalMCPServer entry by ID.
     *
     * This operation will cascade delete any tool linkages in LocalMCPToolDefinitionTable.
     *
     * Note: This does NOT delete the client-side configuration - that's managed by
     * the client application. Authorization should be verified at the route level before
     * calling this method.
     *
     * @param serverId The ID of the server to delete
     * @return Either a [DeleteServerError] if deletion fails, or Unit on success
     */
    suspend fun deleteServer(serverId: Long): Either<DeleteServerError, Unit>

    /**
     * Validates that the given user owns the given server.
     *
     * @param userId the user to validate
     * @param serverId the server being checked
     * @return Either a [ValidateOwnershipError] or Unit when authorized
     */
    suspend fun validateOwnership(userId: Long, serverId: Long): Either<ValidateOwnershipError, Unit>
}
