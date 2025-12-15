package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.server.service.core.error.mcp.DeleteServerError
import eu.torvian.chatbot.server.service.core.error.mcp.ValidateOwnershipError

/**
 * Service interface for managing Local MCP Server IDs and state.
 *
 * This service handles server-side operations for Local MCP Servers, which includes
 * server creation, ownership tracking, and enabled state synchronization. Full MCP
 * server configurations are stored client-side.
 *
 * Note: The client drives all creation and updates via endpoints.
 */
interface LocalMCPServerService {
    /**
     * Creates a new Local MCP Server with initial enabled state.
     *
     * The server generates and stores the ID, userId, and isEnabled state.
     * The client drives creation and provides the initial isEnabled flag.
     * The client will store the full configuration locally using this ID.
     *
     * @param userId The ID of the user who owns this MCP server configuration
     * @param isEnabled The initial enabled/disabled state (from client)
     * @return A generated server ID
     */
    suspend fun createServer(userId: Long, isEnabled: Boolean): Long

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

    /**
     * Updates the enabled state of a LocalMCPServer.
     *
     * This is called when the client syncs the enabled state from the client-side
     * LocalMCPServerLocalTable.isEnabled flag. Authorization (ownership) should be
     * verified at the route level before calling this method.
     *
     * @param serverId The ID of the server to update
     * @param isEnabled The new enabled state
     * @return Either a [DeleteServerError] if the server is not found, or Unit on success
     */
    suspend fun setServerEnabled(serverId: Long, isEnabled: Boolean): Either<DeleteServerError, Unit>
}
