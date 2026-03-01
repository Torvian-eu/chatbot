package eu.torvian.chatbot.app.service.mcp

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.zipWith
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.repository.LocalMCPServerRepository
import eu.torvian.chatbot.app.repository.LocalMCPToolRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

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
 * - Handles data transformation (MCP SDK Tool → LocalMCPToolDefinition)
 * - Pure business logic and workflow coordination
 *
 * @property serverRepository Repository for MCP server configurations
 * @property toolRepository Repository for MCP tool persistence
 * @property mcpClientService Service for MCP operations
 * @property clock Clock instance for generating timestamps
 */
class LocalMCPServerManagerImpl(
    private val serverRepository: LocalMCPServerRepository,
    private val toolRepository: LocalMCPToolRepository,
    private val mcpClientService: MCPClientService,
    private val clock: Clock = Clock.System
) : LocalMCPServerManager {

    companion object {
        private val logger = kmpLogger<LocalMCPServerManagerImpl>()
    }

    override val serverOverviews: Flow<DataState<RepositoryError, List<LocalMCPServerOverview>>> = combine(
        serverRepository.servers,
        mcpClientService.clients,
        toolRepository.mcpTools
    ) { serversState, clientsMap, toolsState ->
        // Zip server and tool states together
        serversState.zipWith(toolsState) { servers, toolsByServerId ->
            // Build overview list from servers, enriched with client and tool data
            servers
                .map { server ->
                    val client = clientsMap[server.id]
                    val tools = toolsByServerId[server.id]

                    LocalMCPServerOverview(
                        serverConfig = server,
                        tools = tools,
                        isConnected = client != null,
                        processStatus = client?.processStatus,
                        connectedAt = client?.connectedAt,
                        lastActivityAt = client?.lastActivityAt,
                        isResponsive = client?.isResponsive
                    )
                }
        }
    }

    override suspend fun loadServers(userId: Long): Either<RepositoryError, Unit> = either {
        logger.info("Loading MCP servers for user $userId")

        // Load server configurations
        serverRepository.loadServers(userId)

        // Load tools
        toolRepository.loadMCPTools().onLeft { repoErr ->
            logger.error("Failed to load MCP tools for user $userId: ${repoErr.message}")
        }.bind()
    }

    override suspend fun testConnectionForNewServer(
        name: String,
        command: String,
        arguments: List<String>,
        environmentVariables: Map<String, String>,
        workingDirectory: String?
    ): Either<TestConnectionError, Int> = either {
        logger.info("Testing new server: $name")

        // Step 1: Create a temporary server config
        val server = LocalMCPServer(
            id = (Long.MIN_VALUE..-1L).random(),
            userId = 1L,
            name = name,
            command = command,
            arguments = arguments,
            environmentVariables = environmentVariables,
            workingDirectory = workingDirectory
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
        command: String,
        arguments: List<String>,
        environmentVariables: Map<String, String>,
        workingDirectory: String?,
        isEnabled: Boolean,
        autoStartOnEnable: Boolean,
        autoStartOnLaunch: Boolean,
        autoStopAfterInactivitySeconds: Int?,
        toolNamePrefix: String?
    ): Either<CreateServerError, LocalMCPServer> = either {
        logger.info("Creating new server: $name")

        // Persist server config to database (no connection or tool discovery)
        val createdServer = serverRepository.createServer(
            name = name,
            description = description,
            command = command,
            arguments = arguments,
            environmentVariables = environmentVariables,
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
        logger.info("Testing connection to MCP server: $serverId")

        // Step 1: Start and connect to the server (if not already connected)
        val isConnected = mcpClientService.isClientRegistered(serverId)
        if (!isConnected) {
            val config = getServerConfig(serverId).mapLeft { error ->
                logger.error("Failed to load config for MCP server $serverId: ${error.message}")
                TestConnectionError.ConfigNotFound(serverId, error)
            }.bind()
            mcpClientService.startAndConnect(config).mapLeft { error ->
                logger.error("Failed to connect to MCP server $serverId: ${error.message}")
                TestConnectionError.ConnectionFailed(serverId, error)
            }.bind()
        }

        try {
            // Step 2: Discover tools (test the connection)
            mcpClientService.discoverTools(serverId)
                .mapLeft { error ->
                    logger.error("Failed to discover tools from MCP server $serverId: ${error.message}")
                    TestConnectionError.DiscoveryFailed(serverId, error)
                }
                .map { tools ->
                    logger.info("Connection test successful for server $serverId: ${tools.size} tools discovered")
                    tools.size
                }
                .bind()
        } finally {
            // Step 3: Cleanup - stop the server if we started it
            if (!isConnected) {
                mcpClientService.stopServer(serverId).onLeft { error ->
                    logger.warn("Failed to stop MCP server $serverId after connection test: ${error.message}")
                }
            }
        }
    }

    override suspend fun refreshTools(serverId: Long): Either<RefreshToolsError, RefreshMCPToolsResponse> = either {
        logger.info("Refreshing tools for MCP server: $serverId")

        // Step 1: Start and connect to the server (if not already connected)
        val isConnected = mcpClientService.isClientRegistered(serverId)
        val config = getServerConfig(serverId).mapLeft { error ->
            logger.error("Failed to load config for MCP server $serverId: ${error.message}")
            RefreshToolsError.ConfigNotFound(serverId, error)
        }.bind()

        if (!isConnected) {
            mcpClientService.startAndConnect(config).mapLeft { error ->
                logger.error("Failed to connect to MCP server $serverId: ${error.message}")
                RefreshToolsError.ConnectionFailed(serverId, error)
            }.bind()
        }

        try {
            // Step 2: Discover current tools via MCP client
            val currentToolDefinitions = mcpClientService.discoverTools(serverId)
                .mapLeft { error ->
                    logger.error("Failed to discover current tools from MCP server $serverId: ${error.message}")
                    RefreshToolsError.DiscoveryFailed(serverId, error)
                }
                .map { mcpTools ->
                    mcpTools.map { convertMCPToolToDefinition(it, config) }
                }
                .bind()

            // Step 3: Call repository to perform differential refresh (repository handles comparison and API call)
            toolRepository.refreshMCPTools(serverId, currentToolDefinitions)
                .mapLeft { error ->
                    logger.error("Failed to refresh tools for MCP server $serverId: ${error.message}")
                    RefreshToolsError.RefreshPersistFailed(serverId, error)
                }
                .onRight { response ->
                    logger.info("Successfully refreshed tools for MCP server $serverId: +${response.addedTools.size} ~${response.updatedTools.size} -${response.deletedTools.size}")
                }
                .bind()
        } finally {
            // Step 4: Cleanup - stop the server if we started it
            if (!isConnected) {
                mcpClientService.stopServer(serverId).onLeft { error ->
                    logger.warn("Failed to stop MCP server $serverId after tool refresh: ${error.message}")
                }
            }
        }
    }

    override suspend fun startServer(serverId: Long): Either<ManageStartServerError, Unit> = either {
        logger.info("Starting MCP server: $serverId")

        // Step 1: Get config from LocalMCPServerRepository
        val config = getServerConfig(serverId).mapLeft { error ->
            logger.error("Failed to load config for MCP server $serverId: ${error.message}")
            ManageStartServerError.ConfigNotFound(serverId, error)
        }.bind()

        // Step 2: Call MCPClientService to start and connect
        mcpClientService.startAndConnect(config).mapLeft { error ->
            logger.error("Failed to start MCP server $serverId: ${error.message}")
            ManageStartServerError.StartFailed(serverId, error)
        }.bind()

        logger.info("Successfully started MCP server $serverId")

        // Step 3: Auto-discover tools if this server has none yet (e.g. newly created server)
        val existingTools = (toolRepository.mcpTools.first() as? DataState.Success)
            ?.data?.get(serverId)
        if (existingTools.isNullOrEmpty()) {
            logger.info("No tools found for MCP server $serverId — running initial tool discovery")
            refreshTools(serverId).onLeft { error ->
                logger.warn("Initial tool discovery failed for MCP server $serverId: ${error.message}")
            }.onRight {
                logger.info("Initial tool discovery completed for MCP server $serverId")
            }
        }
    }

    override suspend fun stopServer(serverId: Long): Either<ManageStopServerError, Unit> = either {
        logger.info("Stopping MCP server: $serverId")

        // Call MCPClientService to stop
        mcpClientService.stopServer(serverId).mapLeft { error ->
            logger.error("Failed to stop MCP server $serverId: ${error.message}")
            ManageStopServerError.StopFailed(serverId, error)
        }.bind()

        logger.info("Successfully stopped MCP server $serverId")
    }

    override suspend fun updateServer(server: LocalMCPServer): Either<UpdateServerError, Unit> = either {
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

    override suspend fun callTool(
        serverId: Long,
        toolName: String,
        arguments: JsonObject
    ): Either<ManageCallToolError, CallToolResult?> = either {
        logger.info("Calling tool '$toolName' on MCP server $serverId")
        logger.debug("Tool arguments: $arguments")

        // Step 1: Ensure server is started and connected
        val isStarted = mcpClientService.isClientRegistered(serverId)
        if (!isStarted) {
            val config = getServerConfig(serverId).mapLeft { error ->
                logger.error("Failed to load config for MCP server $serverId: ${error.message}")
                ManageCallToolError.ConfigNotFound(serverId, error)
            }.bind()
            mcpClientService.startAndConnect(config).mapLeft { error ->
                logger.error("Failed to connect to MCP server $serverId: ${error.message}")
                ManageCallToolError.StartFailed(serverId, error)
            }.bind()
        }

        // Step 2: Call MCPClientService to execute tool
        mcpClientService.callTool(serverId, toolName, arguments).mapLeft { error ->
            logger.error("Failed to call tool '$toolName' on MCP server $serverId: ${error.message}")
            ManageCallToolError.CallFailed(serverId, error)
        }.bind()
    }

    /**
     * Retrieves the server configuration from the repository.
     *
     * @param serverId The ID of the server to retrieve
     * @return Either.Right with the server config or Either.Left with RepositoryError
     */
    private fun getServerConfig(serverId: Long): Either<RepositoryError, LocalMCPServer> {
        return when (val currentState = serverRepository.servers.value) {
            is DataState.Success -> {
                val server = currentState.data.find { it.id == serverId }
                server?.right() ?: RepositoryError.OtherError("MCP server not found: $serverId").left()
            }

            is DataState.Error -> currentState.error.left()
            else -> RepositoryError.OtherError("MCP server repository not loaded").left()
        }
    }

    /**
     * Converts an MCP SDK Tool to a LocalMCPToolDefinition.
     *
     * @param mcpTool The MCP SDK Tool object
     * @param server The full server config (used for serverId and toolNamePrefix)
     * @return LocalMCPToolDefinition ready for persistence
     */
    private fun convertMCPToolToDefinition(
        mcpTool: Tool,
        server: LocalMCPServer
    ): LocalMCPToolDefinition {
        val now = clock.now()

        return LocalMCPToolDefinition(
            id = 0L, // Will be assigned by server
            name = buildToolName(server.toolNamePrefix, mcpTool.name),
            description = mcpTool.description ?: "",
            config = buildJsonObject { },
            inputSchema = Json.encodeToJsonElement(mcpTool.inputSchema) as JsonObject,
            outputSchema = mcpTool.outputSchema?.let { Json.encodeToJsonElement(it) as JsonObject },
            isEnabled = true,
            createdAt = now,
            updatedAt = now,
            serverId = server.id,
            mcpToolName = mcpTool.name
        )
    }

    /**
     * Constructs the tool name the LLM will see by prepending [prefix] to [mcpToolName].
     * If [prefix] is null or blank the raw [mcpToolName] is returned unchanged.
     */
    private fun buildToolName(prefix: String?, mcpToolName: String): String =
        if (prefix.isNullOrBlank()) mcpToolName else "$prefix$mcpToolName"
}