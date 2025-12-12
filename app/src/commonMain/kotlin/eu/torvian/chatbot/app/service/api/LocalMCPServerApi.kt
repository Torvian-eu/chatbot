package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.CreateServerRequest
import eu.torvian.chatbot.common.models.api.mcp.CreateServerResponse
import eu.torvian.chatbot.common.models.api.mcp.ServerIdsResponse

/**
 * Frontend API interface for interacting with Local MCP Server endpoints.
 *
 * This interface defines the operations for managing MCP server creation, deletion,
 * and state synchronization. Implementations use the internal HTTP API. All methods
 * are suspend functions and return [Either<ApiResourceError, T>].
 *
 * **Note**: This API handles server ID management and enabled state. Full MCP server
 * configurations are stored client-side using SQLDelight. Tool operations are handled
 * by [ToolApi]. The client drives all creation and updates via endpoints.
 */
interface LocalMCPServerApi {
    /**
     * Creates a new Local MCP Server with initial enabled state.
     *
     * The server generates a new ID and stores it with the userId and isEnabled flag.
     * The client then stores the full MCP server configuration locally using this ID.
     *
     * Corresponds to `POST /api/v1/local-mcp-servers`.
     *
     * @param request [CreateServerRequest] containing userId and isEnabled state
     * @return [Either.Right] containing [CreateServerResponse] with the generated ID on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun createServer(request: CreateServerRequest): Either<ApiResourceError, CreateServerResponse>

    /**
     * Retrieves all MCP server IDs for the current user.
     *
     * This endpoint returns only the IDs that exist on the server. The client must reconcile
     * this with locally stored configurations.
     *
     * Corresponds to `GET /api/v1/local-mcp-servers/ids`.
     *
     * @return [Either.Right] containing [ServerIdsResponse] with the list of IDs on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun getServerIds(): Either<ApiResourceError, ServerIdsResponse>

    /**
     * Deletes an MCP server from the server.
     *
     * This operation:
     * - Deletes the ID record from LocalMCPServerTable
     * - Triggers CASCADE delete on LocalMCPToolDefinitionTable (removes tool linkages)
     * - Triggers CASCADE delete on ToolDefinitionTable (removes associated tools)
     *
     * The client must separately delete the local configuration from SQLDelight.
     *
     * Corresponds to `DELETE /api/v1/local-mcp-servers/{id}`.
     *
     * @param serverId The unique identifier of the MCP server to delete.
     * @return [Either.Right] with [Unit] on successful deletion (typically HTTP 204 No Content),
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found, unauthorized).
     */
    suspend fun deleteServerId(serverId: Long): Either<ApiResourceError, Unit>

    /**
     * Updates the enabled state of an MCP server on the server.
     *
     * This synchronizes the server-side enabled flag with the client-side
     * LocalMCPServerLocalTable.isEnabled flag. When disabled, all tools from this
     * server become unavailable.
     *
     * Corresponds to `PUT /api/v1/local-mcp-servers/{id}/enabled`.
     *
     * @param serverId The unique identifier of the MCP server
     * @param isEnabled The new enabled/disabled state
     * @return [Either.Right] with [Unit] on successful update (typically HTTP 204 No Content),
     *         or [Either.Left] containing a [ApiResourceError] on failure
     */
    suspend fun setServerEnabled(serverId: Long, isEnabled: Boolean): Either<ApiResourceError, Unit>
}

