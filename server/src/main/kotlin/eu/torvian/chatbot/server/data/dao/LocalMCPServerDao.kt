package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPServerError
import eu.torvian.chatbot.server.data.entities.CreateLocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.LocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.UpdateLocalMCPServerEntity

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
}
