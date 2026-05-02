package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerServiceError

/**
 * Service interface for full Local MCP server configuration management.
 */
interface LocalMCPServerService {
    /**
     * Creates a fully configured Local MCP server.
     *
     * @param userId Owning user identifier.
     * @param request Full create payload.
     * @return Either service error or created server payload.
     */
    suspend fun createServer(
        userId: Long,
        request: CreateLocalMCPServerRequest
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto>

    /**
     * Lists all Local MCP servers owned by a user.
     *
     * @param userId Owning user identifier.
     * @return Either service error or user-owned server list.
     */
    suspend fun getServersByUserId(userId: Long): Either<LocalMCPServerServiceError, List<LocalMCPServerDto>>

    /**
     * Fetches a specific user-owned Local MCP server.
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @return Either service error or matching server payload.
     */
    suspend fun getServerById(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto>

    /**
     * Updates a fully configured user-owned Local MCP server.
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @param request Full update payload.
     * @return Either service error or updated server payload.
     */
    suspend fun updateServer(
        userId: Long,
        serverId: Long,
        request: UpdateLocalMCPServerRequest
    ): Either<LocalMCPServerServiceError, LocalMCPServerDto>

    /**
     * Deletes a user-owned Local MCP server and linked tools.
     *
     * @param userId Owning user identifier.
     * @param serverId Target server identifier.
     * @return Either service error or Unit.
     */
    suspend fun deleteServer(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPServerServiceError, Unit>

    /**
     * Lists Local MCP servers assigned to a specific worker.
     *
     * @param workerId Worker identifier.
     * @return Either service error or worker-assigned server list.
     */
    suspend fun getServersByWorkerId(workerId: Long): Either<LocalMCPServerServiceError, List<LocalMCPServerDto>>

    /**
     * Validates that the given user owns the given server.
     *
     * @param userId User identifier to validate.
     * @param serverId Server identifier being checked.
     * @return Either service error or Unit when authorized.
     */
    suspend fun validateOwnership(userId: Long, serverId: Long): Either<LocalMCPServerServiceError, Unit>

    /**
     * Validates that the given user owns the given worker.
     *
     * @param userId User identifier to validate.
     * @param workerId Worker identifier being checked.
     * @return Either service error or Unit when authorized.
     */
    suspend fun validateWorkerOwnership(userId: Long, workerId: Long): Either<LocalMCPServerServiceError, Unit>
}
