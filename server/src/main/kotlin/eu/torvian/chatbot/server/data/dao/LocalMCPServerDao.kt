package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.entities.CreateLocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.LocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.UpdateLocalMCPServerEntity
import eu.torvian.chatbot.server.data.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPServerError

/**
 * Data Access Object for server-side Local MCP server storage.
 *
 * The server owns full Local MCP server configuration persistence, including
 * worker assignment and environment variable metadata.
 */
interface LocalMCPServerDao {
    /**
     * Creates a fully configured Local MCP server row.
     *
     * @param server Full create payload.
     * @return Newly created persisted entity.
     */
    suspend fun createServer(server: CreateLocalMCPServerEntity): LocalMCPServerEntity

    /**
     * Updates a fully configured Local MCP server row.
     *
     * @param userId Owning user identifier used for authorization.
     * @param serverId Server identifier to update.
     * @param server Full update payload.
     * @return Either unauthorized error or updated entity.
     */
    suspend fun updateServer(
        userId: Long,
        serverId: Long,
        server: UpdateLocalMCPServerEntity
    ): Either<LocalMCPServerError.Unauthorized, LocalMCPServerEntity>

    /**
     * Retrieves a Local MCP server by ID.
     *
     * @param serverId Server identifier.
     * @return Either not-found error or matching entity.
     */
    suspend fun getServerById(serverId: Long): Either<LocalMCPServerError.NotFound, LocalMCPServerEntity>

    /**
     * Retrieves a Local MCP server for a specific owning user.
     *
     * @param userId Owning user identifier.
     * @param serverId Server identifier.
     * @return Either unauthorized error or matching entity.
     */
    suspend fun getServerByIdForUser(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerError.Unauthorized, LocalMCPServerEntity>

    /**
     * Lists all Local MCP servers owned by a user.
     *
     * @param userId Owning user identifier.
     * @return User-owned Local MCP servers.
     */
    suspend fun getServersByUserId(userId: Long): List<LocalMCPServerEntity>

    /**
     * Lists all Local MCP servers assigned to a worker.
     *
     * @param workerId Worker identifier.
     * @return Local MCP servers assigned to the worker.
     */
    suspend fun getServersByWorkerId(workerId: Long): List<LocalMCPServerEntity>

    /**
     * Creates a new Local MCP Server entry and stores the ownership and enabled state.
     *
     * This method creates a new entry in the LocalMCPServerTable with the
     * server-generated ID, userId, and isEnabled flag. The client drives creation
     * and provides the initial isEnabled state. The client will store the full
     * configuration locally using this ID.
     *
     * @param userId The ID of the user who owns this MCP server configuration
     * @param isEnabled The initial enabled/disabled state (synced from client)
     * @return The generated server ID
     */
    @Deprecated("Use createServer(CreateLocalMCPServerEntity)")
    suspend fun createServer(userId: Long, isEnabled: Boolean): Long

    /**
     * Deletes a LocalMCPServer entry by ID.
     *
     * This operation will cascade delete any tool linkages in LocalMCPToolDefinitionTable.
     * Note: This does NOT delete the client-side configuration - that's managed by
     * the client application.
     *
     * @param id The unique identifier of the MCP server to delete
     * @return Either a [DeleteLocalMCPServerError] or Unit on success
     */
    suspend fun deleteById(id: Long): Either<DeleteLocalMCPServerError, Unit>

    /**
     * Retrieves all server IDs owned by a specific user.
     *
     * This is useful for validation and ownership checks. The actual configurations
     * are stored client-side, but this allows the server to verify that a user
     * owns a particular server ID.
     *
     * @param userId The ID of the user
     * @return List of server IDs owned by the user (empty list if none)
     */
    @Deprecated("Use getServersByUserId")
    suspend fun getIdsByUserId(userId: Long): List<Long>

    /**
     * Checks if a LocalMCPServer with the specified ID exists.
     *
     * @param id The server ID to check
     * @return true if the server exists, false otherwise
     */
    suspend fun existsById(id: Long): Boolean

    /**
     * Validates that a user owns a specific LocalMCPServer.
     *
     * This is used for authorization checks before allowing operations on the server.
     *
     * @param userId The ID of the user
     * @param serverId The ID of the server to check
     * @return Either [LocalMCPServerError.Unauthorized] if not owned, or Unit if authorized
     */
    suspend fun validateOwnership(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerError.Unauthorized, Unit>

    /**
     * Updates the enabled state of a LocalMCPServer.
     *
     * This is called when the client syncs the enabled state from the client-side
     * LocalMCPServerLocalTable.isEnabled flag.
     *
     * @param serverId The ID of the server to update
     * @param isEnabled The new enabled state
     * @return Either [LocalMCPServerError.NotFound] if not found, or Unit on success
     */
    @Deprecated("Use updateServer")
    suspend fun setEnabled(serverId: Long, isEnabled: Boolean): Either<LocalMCPServerError.NotFound, Unit>
}

