package eu.torvian.chatbot.app.service.mcp

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Instant
import eu.torvian.chatbot.app.utils.misc.ioDispatcher as defaultIODispatcher

/**
 * Desktop/Android implementation of MCPClientService.
 *
 * This service provides MCP-specific operations by wrapping the MCP Kotlin SDK
 * and coordinating with LocalMCPServerProcessManager for process lifecycle.
 *
 * Design principles:
 * - Pure MCP operations (no Repository/API dependencies)
 * - Stateless except for active client connections
 * - Thread-safe client management using StateFlow
 * - Comprehensive error handling via Either
 *
 * @property processManager The process manager for starting/stopping MCP servers
 * @property clock Clock instance for generating timestamps (injectable for testing)
 * @property serviceScope Coroutine scope for managing client connections (injectable for testing)
 */
class MCPClientServiceImpl(
    private val processManager: LocalMCPServerProcessManager,
    private val clock: Clock = Clock.System,
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = defaultIODispatcher
) : MCPClientService {

    companion object {
        private val logger = kmpLogger<MCPClientServiceImpl>()

        // Client info for MCP SDK
        private const val CLIENT_NAME = "chatbot-mcp-client"
        private const val CLIENT_VERSION = "1.0.0"

        // Connection timeout in seconds
        private const val CONNECTION_TIMEOUT_SECONDS = 60
    }

    // Thread-safe StateFlow of active MCP clients
    private val _clients = MutableStateFlow<Map<Long, MCPClientInternal>>(emptyMap())
    private val clientsInternal: StateFlow<Map<Long, MCPClientInternal>> = _clients.asStateFlow()

    override val clients: Flow<Map<Long, MCPClient>> = clientsInternal.map { internalClients ->
        internalClients.mapValues { (_, internal) -> internal.toPublic() }
    }

    override suspend fun startAndConnect(
        config: LocalMCPServer
    ): Either<StartAndConnectError, Unit> {
        val serverId = config.id
        logger.info("Starting and connecting to MCP server: $serverId (${config.name})")

        // Check if already connected
        if (clientsInternal.value.containsKey(serverId)) {
            logger.warn("MCP server $serverId is already connected")
            return StartAndConnectError.AlreadyConnected(serverId).left()
        }

        // Start the process via ProcessManager
        val processStatus = processManager.startServer(config).getOrElse { error ->
            logger.error("Failed to start MCP server process: ${error.message}")
            return StartAndConnectError.ProcessStartFailed(
                serverId = serverId,
                reason = error.message,
                cause = error.cause
            ).left()
        }

        // Get process streams for STDIO transport
        val inputStream = processManager.getProcessInputStream(serverId)
        val outputStream = processManager.getProcessOutputStream(serverId)

        if (inputStream == null || outputStream == null) {
            logger.error("Failed to get process streams for MCP server $serverId")
            // Clean up the process since we can't connect
            processManager.stopServer(serverId)
            return StartAndConnectError.StreamsUnavailable(
                serverId = serverId,
                reason = "Process streams are null"
            ).left()
        }

        try {
            // Create MCP SDK Client with a unique name per server
            val clientName = buildClientName(config)
            logger.debug("Using MCP client name '$clientName' for server ${config.id}")
            val sdkClient = Client(
                clientInfo = Implementation(
                    name = clientName,
                    version = CLIENT_VERSION
                )
            )

            // Create STDIO transport
            val transport = StdioClientTransport(
                input = inputStream,
                output = outputStream,
                error = processManager.getProcessErrorStream(serverId)
            )

            // Connect to the MCP server with timeout
            try {
                withContext(ioDispatcher) {
                    withTimeout(CONNECTION_TIMEOUT_SECONDS * 1000L) {
                        sdkClient.connect(transport)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                logger.error("Connection to MCP server $serverId timed out after $CONNECTION_TIMEOUT_SECONDS seconds")
                processManager.stopServer(serverId)
                return StartAndConnectError.ConnectionTimeout(
                    serverId = serverId,
                    timeoutSeconds = CONNECTION_TIMEOUT_SECONDS
                ).left()
            }

            // Store the client
            _clients.update { currentClients ->
                currentClients + (serverId to MCPClientInternal(
                    serverConfig = config,
                    processStatus = processStatus,
                    sdkClient = sdkClient,
                    connectedAt = clock.now(),
                    lastActivityAt = clock.now(),
                    lastPing = true
                ))
            }
            // Start the auto-stop timer for this client
            startOrResetAutoStopTimer(serverId)
            logger.info("Successfully connected MCP SDK client to server $serverId")
            return Unit.right()
        } catch (e: Exception) {
            logger.error("Failed to create/connect MCP SDK client for server $serverId", e)
            // Clean up the process
            processManager.stopServer(serverId)
            return StartAndConnectError.SDKConnectionFailed(
                serverId = serverId,
                reason = e.message ?: "Unknown error",
                cause = e
            ).left()
        }
    }

    override suspend fun stopServer(
        serverId: Long
    ): Either<MCPStopServerError, Unit> = stopServerInternal(serverId, fromTimer = false)

    /**
     * Internal implementation of stopServer with control over timer cancellation.
     *
     * @param serverId The ID of the server to stop
     * @param fromTimer Whether this call originates from the auto-stop timer
     */
    private suspend fun stopServerInternal(
        serverId: Long,
        fromTimer: Boolean
    ): Either<MCPStopServerError, Unit> {
        logger.info("Stopping MCP server: $serverId")

        // Remove client from map (atomic operation)
        val mcpClient = clientsInternal.value[serverId]
        if (mcpClient != null) {
            // Cancel any pending auto-stop timer ONLY if not called from the timer itself
            if (!fromTimer) {
                mcpClient.autoStopTimerJob?.cancel()
            }
            _clients.update { it - serverId }
        }

        // Disconnect MCP SDK client if it exists
        if (mcpClient != null) {
            try {
                withContext(ioDispatcher) {
                    mcpClient.sdkClient.close()
                }
                logger.debug("Disconnected MCP SDK client for server $serverId")
            } catch (e: Exception) {
                logger.error("Error disconnecting MCP SDK client for server $serverId", e)
                // Try to stop the process anyway
                processManager.stopServer(serverId).onLeft { error ->
                    if (error !is StopServerError.ProcessNotRunning) {
                        logger.error("Failed to stop MCP server process: ${error.message}")
                    }
                }
                return MCPStopServerError.DisconnectFailed(
                    serverId = serverId,
                    reason = e.message ?: "Unknown error",
                    cause = e
                ).left()
            }
        }

        // Stop the process via ProcessManager
        processManager.stopServer(serverId).onLeft { error ->
            if (error !is StopServerError.ProcessNotRunning) {
                logger.error("Failed to stop MCP server process: ${error.message}")
                return MCPStopServerError.ProcessStopFailed(
                    serverId = serverId,
                    reason = error.message,
                    cause = error.cause
                ).left()
            }
        }

        logger.info("Successfully stopped MCP server $serverId")
        return Unit.right()
    }

    override suspend fun disconnectAll(): Int {
        logger.info("Disconnecting all MCP clients")

        val serverIds = clientsInternal.value.keys.toList()
        var disconnectedCount = 0

        for (serverId in serverIds) {
            val result = stopServer(serverId)
            if (result.isRight()) {
                disconnectedCount++
            }
        }

        logger.info("Disconnected $disconnectedCount MCP clients")
        return disconnectedCount
    }

    override suspend fun discoverTools(
        serverId: Long
    ): Either<DiscoverToolsError, List<Tool>> {
        logger.info("Discovering tools from MCP server: $serverId")
        val client = clientsInternal.value[serverId] ?: return DiscoverToolsError.NotConnected(serverId).left()
        try {
            // Call MCP SDK to list tools
            val toolsResult = withContext(ioDispatcher) {
                client.sdkClient.listTools(
                    request = ListToolsRequest(),
                    options = null
                )
            }
            val tools = toolsResult.tools
            updateActivity(serverId)
            logger.info("Discovered ${tools.size} tools from MCP server $serverId")
            return tools.right()
        } catch (e: Exception) {
            logger.error("Failed to discover tools from MCP server $serverId", e)
            return DiscoverToolsError.SDKListToolsFailed(
                serverId = serverId,
                reason = e.message ?: "Unknown error",
                cause = e
            ).left()
        }
    }

    override suspend fun callTool(
        serverId: Long,
        toolName: String,
        arguments: JsonObject
    ): Either<CallToolError, CallToolResult?> {
        logger.info("Calling tool '$toolName' on MCP server $serverId")
        logger.debug("Tool arguments: $arguments")

        // Ensure server is connected
        val mcpClient = clientsInternal.value[serverId]
            ?: return CallToolError.NotConnected(serverId, toolName).left()

        try {
            // Call MCP SDK to execute tool and return the result directly
            val result = withContext(ioDispatcher) {
                mcpClient.sdkClient.callTool(
                    request = CallToolRequest(
                        params = CallToolRequestParams(
                            name = toolName,
                            arguments = arguments
                        )
                    )
                )
            }
            updateActivity(serverId)
            logger.info("Tool '$toolName' executed successfully on server $serverId")
            return result.right()
        } catch (e: Exception) {
            logger.error("Failed to execute tool '$toolName' on MCP server $serverId", e)
            return CallToolError.SDKCallToolFailed(
                serverId = serverId,
                toolName = toolName,
                reason = e.message ?: "Unknown error",
                cause = e
            ).left()
        }
    }

    override suspend fun getServerStatus(serverId: Long): ProcessStatus {
        return processManager.getServerStatus(serverId).also { status ->
            clientsInternal.value[serverId]?.let { client ->
                _clients.update { currentClients ->
                    currentClients + (serverId to client.copy(processStatus = status))
                }
            }
        }
    }

    override fun isClientRegistered(serverId: Long): Boolean {
        return clientsInternal.value.containsKey(serverId)
    }

    override suspend fun pingClient(serverId: Long): Boolean {
        val mcpClient = clientsInternal.value[serverId] ?: return false
        return withContext(ioDispatcher) {
            try {
                mcpClient.sdkClient.ping()
                _clients.update { currentClients ->
                    currentClients + (serverId to mcpClient.copy(lastPing = true))
                }
                true
            } catch (e: Exception) {
                logger.warn("Error pinging MCP SDK client for server $serverId", e)
                _clients.update { currentClients ->
                    currentClients + (serverId to mcpClient.copy(lastPing = false))
                }
                false
            }
        }
    }

    override fun getClient(serverId: Long): MCPClient? {
        return clientsInternal.value[serverId]?.toPublic()
    }

    override fun listClients(): List<MCPClient> {
        return clientsInternal.value.values.map { it.toPublic() }
    }

    override suspend fun close() {
        disconnectAll()
        serviceScope.cancel()
    }

    // --- Internal helpers ---

    /**
     * Builds a unique, sanitized client name per server.
     *
     * Format: "chatbot-mcp-client-<sanitized-server-name>-<id>"
     *
     * @param config The MCP server configuration
     * @return Sanitized client name
     */
    private fun buildClientName(config: LocalMCPServer): String {
        val idStr = config.id.toString()
        val name = config.name
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .ifBlank { "server" }

        val maxNameLen = maxOf(0, 64 - (CLIENT_NAME.length + idStr.length + 2))
        val namePart = name.take(maxNameLen)

        return "$CLIENT_NAME-$namePart-$idStr"
    }

    /**
     * Starts or resets the auto-stop timer for a server.
     *
     * This method cancels any existing timer for the server and starts a new one.
     * When the timer expires (after effectiveAutoStopSeconds of inactivity),
     * the server is automatically stopped.
     *
     * If the server is configured to never auto-stop, no timer is started.
     *
     * @param serverId The ID of the server to start/reset the timer for
     */
    private fun startOrResetAutoStopTimer(serverId: Long) {
        val mcpClient = clientsInternal.value[serverId] ?: return
        val config = mcpClient.serverConfig

        // Cancel existing timer if any
        mcpClient.autoStopTimerJob?.cancel()

        // Don't start a timer if configured to never stop
        if (config.neverAutoStop) {
            mcpClient.autoStopTimerJob = null
            return
        }

        val effectiveAutoStopSeconds = config.effectiveAutoStopSeconds

        // Start a new timer
        val timerJob = serviceScope.launch {
            try {
                delay(effectiveAutoStopSeconds * 1000L)  // Convert to milliseconds

                // Timer expired - auto-stop the server
                logger.info(
                    "Auto-stopping MCP server $serverId (${config.name}) due to inactivity " +
                            "($effectiveAutoStopSeconds seconds threshold reached)"
                )
                stopServerInternal(serverId, fromTimer = true).onLeft { error ->
                    logger.error("Failed to auto-stop server $serverId: ${error.message}")
                }
            } catch (e: Exception) {
                logger.debug("Auto-stop timer for server $serverId was cancelled or errored: ${e.message}")
            }
        }

        // Update the timer job reference
        _clients.update { currentClients ->
            currentClients[serverId]?.let { client ->
                currentClients + (serverId to client.copy(autoStopTimerJob = timerJob))
            } ?: currentClients
        }
    }

    /**
     * Updates the last activity timestamp for a server and resets its auto-stop timer.
     *
     * @param serverId The ID of the server to update
     */
    private fun updateActivity(serverId: Long) {
        clientsInternal.value[serverId]?.let { client ->
            _clients.update { currentClients ->
                currentClients + (serverId to client.copy(lastActivityAt = clock.now()))
            }
        }
        // Reset auto-stop timer when activity occurs
        startOrResetAutoStopTimer(serverId)
    }
}

/**
 * Internal wrapper for MCP SDK Client with lifecycle tracking.
 *
 * @property serverConfig The full LocalMCPServer configuration if available
 * @property processStatus The last known process status from the ProcessManager
 * @property sdkClient The MCP SDK Client instance
 * @property connectedAt When the client was connected
 * @property lastActivityAt Timestamp of the last operation on this connection
 * @property lastPing Result of the last ping check
 * @property autoStopTimerJob Optional coroutine job for the auto-stop timer (null if no timer active)
 */
private data class MCPClientInternal(
    val serverConfig: LocalMCPServer,
    val processStatus: ProcessStatus,
    val sdkClient: Client,
    val connectedAt: Instant,
    var lastActivityAt: Instant,
    val lastPing: Boolean,
    var autoStopTimerJob: Job? = null
) {
    /**
     * Converts the internal client representation to the public one.
     */
    fun toPublic(): MCPClient = MCPClient(
        serverConfig = serverConfig,
        processStatus = processStatus,
        connectedAt = connectedAt,
        lastActivityAt = lastActivityAt,
        isResponsive = lastPing
    )
}
