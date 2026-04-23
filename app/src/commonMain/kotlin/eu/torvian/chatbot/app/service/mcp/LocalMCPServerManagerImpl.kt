package eu.torvian.chatbot.app.service.mcp

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.zipWith
import eu.torvian.chatbot.app.repository.LocalMCPServerRepository
import eu.torvian.chatbot.app.repository.LocalMCPServerRuntimeStatusRepository
import eu.torvian.chatbot.app.repository.LocalMCPToolRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.time.Clock

/**
 * Desktop/Android implementation of LocalMCPServerManager.
 *
 * This manager orchestrates MCP server workflows by coordinating between:
 * - LocalMCPServerRepository (MCP server configurations)
 * - LocalMCPToolRepository (MCP tool persistence)
 * - MCPClientService (MCP operations)
 *
 * Design principles:
 * - High-level orchestration layer between UI and MCP operations
 * - Coordinates data flow across repositories and services
 * - Handles data transformation (MCP SDK Tool â†’ LocalMCPToolDefinition)
 * - Pure business logic and workflow coordination
 *
 * @property serverRepository Repository for MCP server configurations
 * @property runtimeStatusRepository Repository for worker-backed runtime status snapshots
 * @property toolRepository Repository for MCP tool persistence
 * @property mcpClientService Service for MCP operations
 * @property clock Clock instance for generating timestamps
 */
class LocalMCPServerManagerImpl(
    private val serverRepository: LocalMCPServerRepository,
    private val runtimeStatusRepository: LocalMCPServerRuntimeStatusRepository,
    private val toolRepository: LocalMCPToolRepository,
    private val mcpClientService: MCPClientService,
    private val clock: Clock = Clock.System
) : LocalMCPServerManager {

    companion object {
        private val logger = kmpLogger<LocalMCPServerManagerImpl>()
    }

    override val serverOverviews: Flow<DataState<RepositoryError, List<LocalMCPServerOverview>>> = combine(
        serverRepository.servers,
        runtimeStatusRepository.runtimeStatuses,
        toolRepository.mcpTools
    ) { serversState, runtimeStatusesState, toolsState ->
        serversState
            .zipWith(runtimeStatusesState) { servers, statusesByServerId ->
                servers to statusesByServerId
            }
            .zipWith(toolsState) { (servers, statusesByServerId), toolsByServerId ->
                servers.map { server ->
                    LocalMCPServerOverview(
                        serverConfig = server,
                        tools = toolsByServerId[server.id],
                        runtimeStatus = statusesByServerId[server.id]
                    )
                }
            }
    }

    override suspend fun loadServers(userId: Long): Either<RepositoryError, Unit> = either {
        logger.info("Loading MCP servers for user $userId")

        // Load server configurations.
        serverRepository.loadServers(userId)

        // Load worker-backed runtime statuses.
        runtimeStatusRepository.loadRuntimeStatuses().onLeft { repoErr ->
            logger.error("Failed to load runtime statuses for user $userId: ${repoErr.message}")
        }.bind()

        // Load tools.
        toolRepository.loadMCPTools().onLeft { repoErr ->
            logger.error("Failed to load MCP tools for user $userId: ${repoErr.message}")
        }.bind()
    }

    override suspend fun testConnectionForNewServer(
        name: String,
        command: String,
        arguments: List<String>,
        environmentVariables: List<LocalMCPEnvironmentVariableDto>,
        workingDirectory: String?
    ): Either<TestConnectionError, Int> = either {
        logger.info("Testing new server: $name")

        // Step 1: Create a temporary server config
        val server = LocalMCPServerDto(
            id = (Long.MIN_VALUE..-1L).random(),
            userId = 1L,
            workerId = 0L,
            name = name,
            command = command,
            arguments = arguments,
            environmentVariables = environmentVariables,
            secretEnvironmentVariables = emptyList(),
            workingDirectory = workingDirectory,
            createdAt = clock.now(),
            updatedAt = clock.now()
        )

        // Step 2: Start and connect to the server
        mcpClientService.startAndConnect(server).mapLeft { error ->
            logger.error("Failed to connect to MCP server $name: ${error.message}")
            TestConnectionError.ConnectionFailed(server.id, error)
        }.bind()

        try {
            // Step 3: Discover tools
            val numTools = mcpClientService.discoverTools(server.id)
                .mapLeft { error ->
                    logger.error("Failed to discover tools from MCP server $name: ${error.message}")
                    TestConnectionError.DiscoveryFailed(server.id, error)
                }
                .map { tools ->
                    tools.size
                }
                .bind()

            // Step 4: Return result
            logger.info("Connection test successful for new server $name: $numTools tools discovered")
            return@either numTools
        } finally {
            // Step 5: Cleanup - stop the server
            mcpClientService.stopServer(server.id).onLeft { error ->
                logger.warn("Failed to stop MCP server $name after connection test: ${error.message}")
            }
        }
    }

    override suspend fun createServer(
        name: String,
        description: String?,
        workerId: Long,
        command: String,
        arguments: List<String>,
        environmentVariables: List<LocalMCPEnvironmentVariableDto>,
        secretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto>,
        workingDirectory: String?,
        isEnabled: Boolean,
        autoStartOnEnable: Boolean,
        autoStartOnLaunch: Boolean,
        autoStopAfterInactivitySeconds: Int?,
        toolNamePrefix: String?
    ): Either<CreateServerError, LocalMCPServerDto> = either {
        logger.info("Creating new server: $name")

        // Persist server config through repository/server API (no connection or tool discovery)
        val createdServer = serverRepository.createServer(
            workerId = workerId,
            name = name,
            description = description,
            command = command,
            arguments = arguments,
            environmentVariables = environmentVariables,
            secretEnvironmentVariables = secretEnvironmentVariables,
            workingDirectory = workingDirectory,
            isEnabled = isEnabled,
            autoStartOnEnable = autoStartOnEnable,
            autoStartOnLaunch = autoStartOnLaunch,
            autoStopAfterInactivitySeconds = autoStopAfterInactivitySeconds,
            toolNamePrefix = toolNamePrefix
        )
            .mapLeft { error ->
                logger.error("Failed to create MCP server $name: ${error.message}")
                CreateServerError.ServerPersistenceFailed(error)
            }
            .bind()

        logger.info("Successfully created server $name (ID: ${createdServer.id})")
        createdServer
    }

    override suspend fun testConnection(serverId: Long): Either<TestConnectionError, Int> = either {
        logger.info("Testing connection through server runtime control for MCP server: $serverId")

        val response = serverRepository.testConnection(serverId).mapLeft { error ->
            logger.error("Server runtime-control test-connection failed for MCP server $serverId: ${error.message}")
            TestConnectionError.RuntimeControlFailed(serverId, error)
        }.bind()

        response.discoveredToolCount
    }

    override suspend fun refreshTools(serverId: Long): Either<RefreshToolsError, RefreshMCPToolsResponse> = either {
        logger.info("Refreshing tools for MCP server: $serverId")

        val refreshResponse = serverRepository.refreshTools(serverId)
            .mapLeft { error ->
                logger.error("Failed to refresh tools for MCP server $serverId: ${error.message}")
                RefreshToolsError.RefreshPersistFailed(serverId, error)
            }
            .bind()

        // Sync local cache to the canonical server state after server-owned refresh orchestration.
        toolRepository.loadMCPTools()
            .mapLeft { error ->
                logger.error("Failed to reload MCP tools after refresh for server $serverId: ${error.message}")
                RefreshToolsError.RefreshPersistFailed(serverId, error)
            }
            .bind()

        logger.info("Successfully refreshed tools for MCP server $serverId: +${refreshResponse.addedTools.size} ~${refreshResponse.updatedTools.size} -${refreshResponse.deletedTools.size}")
        refreshResponse
    }

    override suspend fun startServer(serverId: Long): Either<ManageStartServerError, Unit> = either {
        logger.info("Starting MCP server through server runtime control: $serverId")

        serverRepository.startServer(serverId).mapLeft { error ->
            logger.error("Server runtime-control start failed for MCP server $serverId: ${error.message}")
            ManageStartServerError.RuntimeControlFailed(serverId, error)
        }.bind()
    }

    override suspend fun stopServer(serverId: Long): Either<ManageStopServerError, Unit> = either {
        logger.info("Stopping MCP server through server runtime control: $serverId")

        serverRepository.stopServer(serverId).mapLeft { error ->
            logger.error("Server runtime-control stop failed for MCP server $serverId: ${error.message}")
            ManageStopServerError.RuntimeControlFailed(serverId, error)
        }.bind()
    }

    override suspend fun updateServer(server: LocalMCPServerDto): Either<UpdateServerError, Unit> = either {
        logger.info("Updating MCP server: ${server.id}")

        // Step 1: Get the old configuration to check if isEnabled changed
        val oldServer = getServerConfig(server.id).mapLeft { error ->
            logger.error("Failed to load current config for MCP server ${server.id}: ${error.message}")
            UpdateServerError.ServerUpdateFailed(server.id, error)
        }.bind()

        // Step 2: Persist the updated server configuration (no restart or tool rediscovery)
        serverRepository.updateServer(server).mapLeft { error ->
            logger.error("Failed to update MCP server configuration ${server.id}: ${error.message}")
            UpdateServerError.ServerUpdateFailed(server.id, error)
        }.bind()

        logger.info("Server configuration saved for MCP server ${server.id}")

        // Step 3: Invalidate enabled tools cache if enabled state changed
        if (oldServer.isEnabled != server.isEnabled) {
            logger.info("Server enabled state changed, invalidating enabled tools cache for all sessions")
            toolRepository.invalidateEnabledToolsCache()
        }
    }

    override suspend fun deleteServer(serverId: Long): Either<DeleteServerError, Unit> = either {
        logger.info("Deleting MCP server: $serverId")

        // Step 1: Stop the server if it's running
        if (mcpClientService.isClientRegistered(serverId)) {
            logger.info("Stopping MCP server $serverId before deletion")
            mcpClientService.stopServer(serverId).mapLeft { error ->
                logger.error("Failed to stop MCP server $serverId before deletion: ${error.message}")
                DeleteServerError.StopFailed(serverId, error)
            }.bind()
        }

        // Step 2: Delete the server configuration (server-side will cascade delete tools)
        logger.info("Deleting server configuration for MCP server $serverId")
        serverRepository.deleteServer(serverId).mapLeft { error ->
            logger.error("Failed to delete MCP server configuration $serverId: ${error.message}")
            DeleteServerError.ServerDeletionFailed(serverId, error)
        }.bind()

        // Step 3: Remove the tools from the tool repository cache
        toolRepository.removeToolsFromCache(serverId)

        logger.info("Successfully deleted MCP server $serverId")
    }

    /**
     * Retrieves the server configuration from the repository.
     *
     * @param serverId The ID of the server to retrieve
     * @return Either.Right with the server config or Either.Left with RepositoryError
     */
    private fun getServerConfig(serverId: Long): Either<RepositoryError, LocalMCPServerDto> {
        return when (val currentState = serverRepository.servers.value) {
            is DataState.Success -> {
                val server = currentState.data.find { it.id == serverId }
                server?.right() ?: RepositoryError.OtherError("MCP server not found: $serverId").left()
            }

            is DataState.Error -> currentState.error.left()
            else -> RepositoryError.OtherError("MCP server repository not loaded").left()
        }
    }

}