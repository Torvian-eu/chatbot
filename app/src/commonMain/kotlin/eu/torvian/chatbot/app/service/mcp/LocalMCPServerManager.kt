package eu.torvian.chatbot.app.service.mcp

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import kotlinx.coroutines.flow.Flow

/**
 * High-level orchestration service for Local MCP Server workflows.
 *
 * This manager coordinates between:
 * - LocalMCPServerRepository (MCP server configurations)
 * - LocalMCPToolRepository (MCP tool persistence)
 * - server-owned runtime-control APIs exposed through the repository layer
 *
 * Design principles:
 * - High-level orchestration layer between UI and MCP operations
 * - Coordinates data flow across repositories and services
 * - Handles data transformation (MCP SDK Tool â†’ LocalMCPToolDefinition)
 * - Does not manage state (that's Repository's job)
 * - Does not manage processes (that responsibility now stays on the worker/server boundary)
 * - Does not call API directly (that's Repository's job)
 * - Pure business logic and workflow coordination
 *
 */
interface LocalMCPServerManager {

    /**
     * StateFlow providing reactive updates of the aggregate status of all MCP servers.
     *
     * This is a derived state computed from:
     * - LocalMCPServerRepository.servers (MCP server configurations)
     * - LocalMCPToolRepository.tools (MCP tool definitions organized by server ID)
     *
     * The collection provides complete status information for each server,
     * including configuration, tools, process status, connection status, and responsiveness.
     *
     * Automatically updates whenever any of the source states change.
     *
     * The DataState reflects the loading state of the underlying server data:
     * - Idle: No data loaded yet
     * - Loading: Server data is being loaded
     * - Success: Server overviews are available (list may be empty if no servers configured)
     * - Error: Failed to load server data from repository
     *
     * @return StateFlow<DataState<RepositoryError, List<LocalMCPServerOverview>>> list of server overviews
     */
    val serverOverviews: Flow<DataState<RepositoryError, List<LocalMCPServerOverview>>>

    /**
     * Loads all MCP servers and their tools for a specific user.
     *
     * This operation:
     * 1. Calls LocalMCPServerRepository to load servers for the user
     * 2. Calls LocalMCPToolRepository to load all MCP tools
     *
     * @param userId The ID of the user whose servers to load
     * @return Either.Right(Unit) on success, or Either.Left(RepositoryError) on failure
     */
    suspend fun loadServers(userId: Long): Either<RepositoryError, Unit>

    /**
     * Tests connection to a new MCP server and returns the count of discovered tools.
     *
     * This operation:
     * 1. Builds a draft connection request from the unsaved form state.
     * 2. Delegates the draft test to the repository, which calls the server-owned API.
     * 3. Returns the discovered tool count from the runtime response.
     *
     * Server config is NOT persisted during testing.
     *
     * @param workerId Worker assignment used for server-owned runtime routing.
     * @param name The name of the MCP server
     * @param command The command to start the MCP server
     * @param arguments The arguments to start the MCP server
     * @param environmentVariables The environment variables to start the MCP server
     * @param secretEnvironmentVariables Secret environment variables to start the MCP server
     * @param workingDirectory The working directory to start the MCP server
     * @return Either.Right with tool count on success, or Either.Left with TestConnectionError on failure
     */
    suspend fun testConnectionForNewServer(
        workerId: Long,
        name: String,
        command: String,
        arguments: List<String>,
        environmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList(),
        secretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList(),
        workingDirectory: String? = null
    ): Either<TestConnectionError, Int>


    /**
     * Creates a new MCP server configuration and persists it through the server API.
     *
     * This operation only saves the configuration. No process is started,
     * no connection is made, and no tools are discovered. Use [testConnectionForNewServer]
     * to verify the configuration before saving, and [refreshTools] after the server
     * has been started to populate its tool list.
     *
     * @param name The name of the MCP server
     * @param description Optional description of the MCP server
     * @param workerId Worker assignment used by server-side MCP execution routing
     * @param command The command to start the MCP server
     * @param arguments The arguments to start the MCP server
     * @param environmentVariables Non-secret environment variables
     * @param secretEnvironmentVariables Secret environment variables
     * @param workingDirectory The working directory to start the MCP server
     * @param isEnabled Whether the server is globally enabled (if false, tools cannot be enabled for any session)
     * @param autoStartOnEnable Whether the server should be started when a tool is enabled for a session
     * @param autoStartOnLaunch Whether the server should be started when the application launches
     * @param autoStopAfterInactivitySeconds The number of seconds after which the server should be stopped if no tool calls are made
     * @return Either.Right with created server on success, or Either.Left with CreateServerError on failure
     */
    suspend fun createServer(
        name: String,
        description: String? = null,
        workerId: Long,
        command: String,
        arguments: List<String>,
        environmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList(),
        secretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList(),
        workingDirectory: String? = null,
        isEnabled: Boolean = true,
        autoStartOnEnable: Boolean = false,
        autoStartOnLaunch: Boolean = false,
        autoStopAfterInactivitySeconds: Int? = null,
        toolNamePrefix: String? = null
    ): Either<CreateServerError, LocalMCPServerDto>

    /**
     * Tests connection to an existing MCP server through server-owned runtime control.
     *
     * Runtime execution ownership for this operation is server-side; this manager delegates
     * to repository/API wiring and returns the discovered tool count from the server response.
     *
     * @param serverId The ID of the MCP server to test
     * @return Either.Right with tool count on success, or Either.Left with TestConnectionError on failure
     */
    suspend fun testConnection(serverId: Long): Either<TestConnectionError, Int>

    /**
     * Refreshes tools for an MCP server through server-owned runtime control.
     *
     * Runtime execution ownership for this operation is server-side; this manager delegates
     * to repository/API wiring and returns the server response.
     *
     * @param serverId The ID of the MCP server
     * @return Either.Right with refresh summary on success, or Either.Left with RefreshToolsError on failure
     */
    suspend fun refreshTools(serverId: Long): Either<RefreshToolsError, RefreshMCPToolsResponse>

    /**
     * Starts MCP runtime execution through server-owned runtime control.
     *
     * @param serverId The ID of the MCP server to start
     * @return Either.Right with Unit on success, or Either.Left with ManageStartServerError on failure
     */
    suspend fun startServer(serverId: Long): Either<ManageStartServerError, Unit>

    /**
     * Stops MCP runtime execution through server-owned runtime control.
     *
     * @param serverId The ID of the MCP server to stop
     * @return Either.Right with Unit on success, or Either.Left with ManageStopServerError on failure
     */
    suspend fun stopServer(serverId: Long): Either<ManageStopServerError, Unit>

    /**
     * Updates an existing MCP server configuration in the database.
     *
     * This operation only saves the configuration. If the server is currently running,
     * it will continue with its old configuration until it is restarted.
     * No restart, reconnection, or tool rediscovery is performed automatically.
     * If the [LocalMCPServerDto.isEnabled] state changed, the enabled tools cache is invalidated.
     *
     * @param server The updated MCP server configuration
     * @return Either.Right with Unit on success, or Either.Left with UpdateServerError on failure
     */
    suspend fun updateServer(server: LocalMCPServerDto): Either<UpdateServerError, Unit>

    /**
     * Deletes an MCP server and all its associated tools.
     *
     * This operation delegates deletion to the server-owned repository/API and then clears the
     * local tool cache so the UI no longer shows stale data.
     *
     * @param serverId The ID of the MCP server to delete
     * @return Either.Right with Unit on success, or Either.Left with DeleteServerError on failure
     */
    suspend fun deleteServer(serverId: Long): Either<DeleteServerError, Unit>

}
