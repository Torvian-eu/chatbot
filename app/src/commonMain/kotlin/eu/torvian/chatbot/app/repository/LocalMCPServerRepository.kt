package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerConnectionResponse
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing server-owned Local MCP server configurations.
 *
 * This repository serves as the app-side access layer for Local MCP server CRUD operations,
 * using the backend API as persistence source-of-truth while exposing a reactive in-memory cache.
 *
 * The repository maintains an internal cache of MCP server data and automatically updates
 * all observers when changes occur, ensuring data consistency across the application.
 *
 * **Persistence architecture:**
 * - **Server-side**: full MCP server configuration is persisted and authoritative
 * - **Client-side**: reactive in-memory cache for UI state propagation
 * - **Local SQLDelight**: no longer the authoritative MCP server store
 *
 * **Separation of Concerns:**
 * - LocalMCPServerRepository manages MCP server state (this interface)
 * - ToolRepository manages tool state (including MCP tools)
 * - LocalMCPServerManager orchestrates operations between the two (US2.2)
 */
interface LocalMCPServerRepository {

    /**
     * Reactive stream of all MCP server configurations for the current user.
     *
     * This StateFlow provides real-time updates whenever the MCP server data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of all MCP servers wrapped in DataState
     */
    val servers: StateFlow<DataState<RepositoryError, List<LocalMCPServerDto>>>

    /**
     * Loads all MCP server configurations for the current user from the server API.
     *
     * This operation fetches the latest MCP server data and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @param userId The ID of the current user whose MCP servers should be loaded.
     */
    suspend fun loadServers(userId: Long)

    /**
     * Creates a new MCP server configuration.
     *
     * This operation persists the full configuration via server API and updates the in-memory
     * cache with the canonical server response.
     *
     * Upon successful creation, the new server is automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param workerId Worker assignment used by server/worker execution routing
     * @param name The name of the MCP server
     * @param description Optional description of the MCP server
     * @param command The command to start the MCP server
     * @param arguments The arguments to start the MCP server
     * @param environmentVariables Non-secret environment variables
     * @param secretEnvironmentVariables Secret environment variables
     * @param workingDirectory The working directory to start the MCP server
     * @param isEnabled Whether the server is globally enabled (if false, tools cannot be enabled for any session)
     * @param autoStartOnEnable Whether the server should be started when a tool is enabled for a session
     * @param autoStartOnLaunch Whether the server should be started when the application launches
     * @param autoStopAfterInactivitySeconds The number of seconds after which the server should be stopped if no tool calls are made
     * @return Either.Right with the created LocalMCPServerDto on success, or Either.Left with RepositoryError on failure
     */
    suspend fun createServer(
        workerId: Long,
        name: String,
        description: String?,
        command: String,
        arguments: List<String>,
        environmentVariables: List<LocalMCPEnvironmentVariableDto>,
        secretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto>,
        workingDirectory: String?,
        isEnabled: Boolean,
        autoStartOnEnable: Boolean,
        autoStartOnLaunch: Boolean,
        autoStopAfterInactivitySeconds: Int?,
        toolNamePrefix: String? = null,
    ): Either<RepositoryError, LocalMCPServerDto>

    /**
     * Updates an existing MCP server configuration.
     *
     * This operation updates server-side persistence and then refreshes the in-memory cache
     * with the canonical response payload.
     *
     * Upon successful update, the modified server replaces the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param server The updated MCP server object with the same ID as the existing server
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateServer(server: LocalMCPServerDto): Either<RepositoryError, Unit>

    /**
     * Deletes an MCP server configuration.
     *
     * This operation deletes the configuration through the server API and removes it from the
     * in-memory cache.
     *
     * Upon successful deletion, the server is automatically removed from the internal
     * StateFlow, triggering updates to all observers.
     *
     * **Note**: Associated tools are handled by the server-side CASCADE delete on
     * LocalMCPToolDefinitionTable. ToolRepository should invalidate its cache after
     * server deletion to reflect removed tools.
     *
     * @param serverId The unique identifier of the MCP server to delete
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteServer(serverId: Long): Either<RepositoryError, Unit>

    /**
     * Requests server-owned runtime start for the target Local MCP server.
     *
     * @param serverId The unique identifier of the Local MCP server to start.
     * @return Either.Right with Unit on success, or Either.Left with RepositoryError on failure.
     */
    suspend fun startServer(serverId: Long): Either<RepositoryError, Unit>

    /**
     * Requests server-owned runtime stop for the target Local MCP server.
     *
     * @param serverId The unique identifier of the Local MCP server to stop.
     * @return Either.Right with Unit on success, or Either.Left with RepositoryError on failure.
     */
    suspend fun stopServer(serverId: Long): Either<RepositoryError, Unit>

    /**
     * Requests a server-owned runtime connection test for a persisted Local MCP server.
     *
     * @param serverId The unique identifier of the Local MCP server to test.
     * @return Either.Right with the test response payload on success, or Either.Left with RepositoryError on failure.
     */
    suspend fun testConnection(serverId: Long): Either<RepositoryError, TestLocalMCPServerConnectionResponse>

    /**
     * Requests a server-owned runtime tool refresh for a persisted Local MCP server.
     *
     * @param serverId The unique identifier of the Local MCP server to refresh.
     * @return Either.Right with refresh summary payload on success, or Either.Left with RepositoryError on failure.
     */
    suspend fun refreshTools(serverId: Long): Either<RepositoryError, RefreshMCPToolsResponse>
}
