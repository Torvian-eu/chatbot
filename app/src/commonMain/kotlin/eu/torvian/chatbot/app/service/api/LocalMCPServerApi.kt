package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerConnectionResponse
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest

/**
 * Frontend API interface for interacting with Local MCP Server endpoints.
 *
 * This interface defines full CRUD operations against the server-owned Local MCP server
 * configuration model. Implementations use the internal HTTP API and return
 * [Either<ApiResourceError, T>] for explicit error handling.
 */
interface LocalMCPServerApi {
    /**
     * Creates a fully configured Local MCP server record on the backend.
     *
     * Corresponds to `POST /api/v1/local-mcp-servers`.
     *
     * @param request Full create payload for server-side persistence.
     * @return Created server as returned by the server API.
     */
    suspend fun createServer(request: CreateLocalMCPServerRequest): Either<ApiResourceError, LocalMCPServerDto>

    /**
     * Lists all Local MCP servers for the authenticated user.
     *
     * Corresponds to `GET /api/v1/local-mcp-servers`.
     *
     * @return Local MCP server configurations returned by the backend.
     */
    suspend fun getServers(): Either<ApiResourceError, List<LocalMCPServerDto>>

    /**
     * Retrieves one Local MCP server by its identifier.
     *
     * Corresponds to `GET /api/v1/local-mcp-servers/{id}`.
     *
     * @param serverId Identifier of the Local MCP server to load.
     * @return Loaded Local MCP server.
     */
    suspend fun getServerById(serverId: Long): Either<ApiResourceError, LocalMCPServerDto>

    /**
     * Updates a fully configured Local MCP server record.
     *
     * Corresponds to `PUT /api/v1/local-mcp-servers/{id}`.
     *
     * @param serverId Server identifier to update.
     * @param request Full update payload for server-side persistence.
     * @return Updated server returned by the backend.
     */
    suspend fun updateServer(
        serverId: Long,
        request: UpdateLocalMCPServerRequest
    ): Either<ApiResourceError, LocalMCPServerDto>

    /**
     * Deletes a Local MCP server from the backend.
     *
     * Corresponds to `DELETE /api/v1/local-mcp-servers/{id}`.
     *
     * @param serverId Identifier of the server to delete.
     * @return [Unit] when deletion succeeds.
     */
    suspend fun deleteServer(serverId: Long): Either<ApiResourceError, Unit>

    /**
     * Starts runtime execution for a persisted Local MCP server.
     *
     * Corresponds to `POST /api/v1/local-mcp-servers/{id}/start`.
     *
     * @param serverId Identifier of the server to start.
     * @return [Unit] when the runtime-control request succeeds.
     */
    suspend fun startServer(serverId: Long): Either<ApiResourceError, Unit>

    /**
     * Stops runtime execution for a persisted Local MCP server.
     *
     * Corresponds to `POST /api/v1/local-mcp-servers/{id}/stop`.
     *
     * @param serverId Identifier of the server to stop.
     * @return [Unit] when the runtime-control request succeeds.
     */
    suspend fun stopServer(serverId: Long): Either<ApiResourceError, Unit>

    /**
     * Tests runtime connectivity for a persisted Local MCP server.
     *
     * Corresponds to `POST /api/v1/local-mcp-servers/{id}/test-connection`.
     *
     * @param serverId Identifier of the server to test.
     * @return Test response payload returned by the backend.
     */
    suspend fun testConnection(serverId: Long): Either<ApiResourceError, TestLocalMCPServerConnectionResponse>

    /**
     * Refreshes tools through server-owned runtime control for a persisted Local MCP server.
     *
     * Corresponds to `POST /api/v1/local-mcp-servers/{id}/refresh-tools`.
     *
     * @param serverId Identifier of the server to refresh.
     * @return Refresh summary payload returned by the backend.
     */
    suspend fun refreshTools(serverId: Long): Either<ApiResourceError, RefreshMCPToolsResponse>
}

