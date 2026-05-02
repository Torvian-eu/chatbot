package eu.torvian.chatbot.server.worker.mcp.runtimecontrol

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerConnectionResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerDraftConnectionRequest

/**
 * Service abstraction for server-owned runtime control operations of Local MCP servers.
 *
 * This abstraction is intentionally independent from persistence CRUD concerns so the
 * implementation can be replaced later by worker-command orchestration without changing
 * route contracts.
 */
interface LocalMCPRuntimeControlService {
    /**
     * Starts runtime execution for a user-owned Local MCP server.
     *
     * @param userId Authenticated user identifier.
     * @param serverId Local MCP server identifier to start.
     * @return Either runtime-control error or Unit on success.
     */
    suspend fun startServer(userId: Long, serverId: Long): Either<LocalMCPRuntimeControlError, Unit>

    /**
     * Stops runtime execution for a user-owned Local MCP server.
     *
     * @param userId Authenticated user identifier.
     * @param serverId Local MCP server identifier to stop.
     * @return Either runtime-control error or Unit on success.
     */
    suspend fun stopServer(userId: Long, serverId: Long): Either<LocalMCPRuntimeControlError, Unit>

    /**
     * Tests runtime connectivity for a user-owned Local MCP server.
     *
     * @param userId Authenticated user identifier.
     * @param serverId Local MCP server identifier to test.
     * @return Either runtime-control error or deterministic connection-test response.
     */
    suspend fun testConnection(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeControlError, TestLocalMCPServerConnectionResponse>

    /**
     * Refreshes tools for a user-owned Local MCP server through runtime control.
     *
     * @param userId Authenticated user identifier.
     * @param serverId Local MCP server identifier to refresh.
     * @return Either runtime-control error or refresh summary payload.
     */
    suspend fun refreshTools(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeControlError, RefreshMCPToolsResponse>

    /**
     * Reads runtime status for one user-owned Local MCP server.
     *
     * @param userId Authenticated user identifier.
     * @param serverId Local MCP server identifier.
     * @return Either runtime-control error or runtime-status DTO.
     */
    suspend fun getRuntimeStatus(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeControlError, LocalMcpServerRuntimeStatusDto>

    /**
     * Lists runtime statuses for all Local MCP servers owned by the authenticated user.
     *
     * @param userId Authenticated user identifier.
     * @return Either runtime-control error or runtime-status DTO list.
     */
    suspend fun listRuntimeStatuses(userId: Long): Either<LocalMCPRuntimeControlError, List<LocalMcpServerRuntimeStatusDto>>

    /**
     * Tests runtime connectivity for a draft Local MCP server configuration.
     *
     * @param userId Authenticated user identifier.
     * @param request Draft server configuration to test.
     * @return Either runtime-control error or deterministic connection-test response.
     */
    suspend fun testDraftConnection(
        userId: Long,
        request: TestLocalMCPServerDraftConnectionRequest
    ): Either<LocalMCPRuntimeControlError, TestLocalMCPServerConnectionResponse>
}
