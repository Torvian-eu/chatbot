package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.GenerateServerIdResponse
import eu.torvian.chatbot.common.models.api.mcp.ServerIdsResponse

/**
 * Frontend API interface for interacting with Local MCP Server endpoints.
 *
 * This interface defines the operations for managing MCP server ID generation and deletion.
 * Implementations use the internal HTTP API. All methods are suspend functions and return
 * [Either<ApiResourceError, T>].
 *
 * **Note**: This API only handles server ID management. Full MCP server configurations are
 * stored client-side using SQLDelight. Tool operations are handled by [ToolApi].
 */
interface LocalMCPServerApi {
    /**
     * Generates a new unique ID for an MCP server.
     *
     * The server creates a new ID and stores it along with the userId in LocalMCPServerTable.
     * The client then stores the full MCP server configuration locally using this ID.
     *
     * Corresponds to `POST /api/v1/mcp-servers/generate-id`.
     *
     * @return [Either.Right] containing [GenerateServerIdResponse] with the generated ID on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun generateServerId(): Either<ApiResourceError, GenerateServerIdResponse>

    /**
     * Retrieves all MCP server IDs for the current user.
     *
     * This endpoint returns only the IDs that exist on the server. The client must reconcile
     * this with locally stored configurations.
     *
     * Corresponds to `GET /api/v1/mcp-servers/ids`.
     *
     * @return [Either.Right] containing [ServerIdsResponse] with the list of IDs on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun getServerIds(): Either<ApiResourceError, ServerIdsResponse>

    /**
     * Deletes an MCP server ID from the server.
     *
     * This operation:
     * - Deletes the ID record from LocalMCPServerTable
     * - Triggers CASCADE delete on LocalMCPToolDefinitionTable (removes tool linkages)
     * - Triggers CASCADE delete on ToolDefinitionTable (removes associated tools)
     *
     * The client must separately delete the local configuration from SQLDelight.
     *
     * Corresponds to `DELETE /api/v1/mcp-servers/{id}`.
     *
     * @param serverId The unique identifier of the MCP server to delete.
     * @return [Either.Right] with [Unit] on successful deletion (typically HTTP 204 No Content),
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found, unauthorized).
     */
    suspend fun deleteServerId(serverId: Long): Either<ApiResourceError, Unit>
}

