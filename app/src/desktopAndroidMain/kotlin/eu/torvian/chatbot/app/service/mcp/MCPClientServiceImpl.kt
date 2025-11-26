package eu.torvian.chatbot.app.service.mcp

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop/Android implementation of MCPClientService.
 *
 * This service provides MCP-specific operations by wrapping the MCP Kotlin SDK
 * and coordinating with LocalMCPServerProcessManager for process lifecycle.
 *
 * Design principles:
 * - Pure MCP operations (no Repository/API dependencies)
 * - Stateless except for active client connections
 * - Thread-safe client management using ConcurrentHashMap
 * - Comprehensive error handling via Either
 *
 * @property processManager The process manager for starting/stopping MCP servers
 * @property clock Clock instance for generating timestamps (injectable for testing)
 */
class MCPClientServiceImpl(
    private val processManager: LocalMCPServerProcessManager,
    private val clock: Clock = Clock.System
) : MCPClientService {

    companion object {
        private val logger = kmpLogger<MCPClientServiceImpl>()

        // Client info for MCP SDK
        private const val CLIENT_NAME = "chatbot-mcp-client"
        private const val CLIENT_VERSION = "1.0.0"
    }

    // Thread-safe map of active MCP clients
    private val clients = ConcurrentHashMap<Long, MCPClientInternal>()

    override suspend fun startAndConnect(
        config: LocalMCPServer
    ): Either<StartAndConnectError, Unit> {
        val serverId = config.id
        logger.info("Starting and connecting to MCP server: $serverId (${config.name})")

        // Check if already connected
        if (clients.containsKey(serverId)) {
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
                output = outputStream
            )

            // Connect to the MCP server
            withContext(Dispatchers.IO) {
                sdkClient.connect(transport)
            }

            // Store the client
            clients[serverId] = MCPClientInternal(
                serverConfig = config,
                processStatus = processStatus,
                sdkClient = sdkClient,
                connectedAt = clock.now(),
                lastPing = true
            )
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
    ): Either<MCPStopServerError, Unit> {
        logger.info("Stopping MCP server: $serverId")

        // Remove client from map (atomic operation)
        val mcpClient = clients.remove(serverId)

        // Disconnect MCP SDK client if it exists
        if (mcpClient != null) {
            try {
                withContext(Dispatchers.IO) {
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

        val serverIds = clients.keys.toList()
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
        val client = clients[serverId] ?: return DiscoverToolsError.NotConnected(serverId).left()
        try {
            // Call MCP SDK to list tools
            val toolsResult = withContext(Dispatchers.IO) {
                client.sdkClient.listTools(
                    request = ListToolsRequest(),
                    options = null
                )
            }
            val tools = toolsResult.tools
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
        arguments: Map<String, Any?>
    ): Either<CallToolError, CallToolResultBase?> {
        logger.info("Calling tool '$toolName' on MCP server $serverId")
        logger.debug("Tool arguments: $arguments")

        // Ensure server is connected
        val mcpClient = clients[serverId]
            ?: return CallToolError.NotConnected(serverId, toolName).left()

        try {
            // Call MCP SDK to execute tool and return the result directly
            val result = withContext(Dispatchers.IO) {
                mcpClient.sdkClient.callTool(
                    name = toolName,
                    arguments = arguments
                )
            }
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
            clients[serverId]?.let {
                clients[serverId] = it.copy(processStatus = status)
            }
        }
    }

    override fun isClientRegistered(serverId: Long): Boolean {
        return clients.containsKey(serverId)
    }

    override suspend fun pingClient(serverId: Long): Boolean {
        val mcpClient = clients[serverId] ?: return false
        return withContext(Dispatchers.IO) {
            try {
                mcpClient.sdkClient.ping()
                clients[serverId] = mcpClient.copy(lastPing = true)
                true
            } catch (e: Exception) {
                logger.warn("Error pinging MCP SDK client for server $serverId", e)
                clients[serverId] = mcpClient.copy(lastPing = false)
                false
            }
        }
    }

    override fun getClient(serverId: Long): MCPClient? {
        return clients[serverId]?.let { toPublicClient(it) }
    }

    override fun listClients(): List<MCPClient> {
        return clients.values.map { toPublicClient(it) }.toList()
    }

    override fun close() {
        runBlocking { disconnectAll() }
    }

    /**
     * Converts internal MCPClientInternal to public MCPClient representation.
     *
     * @param internal The internal MCPClientInternal object
     * @return Public MCPClient representation
     */
    private fun toPublicClient(internal: MCPClientInternal): MCPClient = MCPClient(
        serverConfig = internal.serverConfig,
        processStatus = internal.processStatus,
        connectedAt = internal.connectedAt,
        isResponsive = internal.lastPing
    )

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
}

/**
 * Internal wrapper for MCP SDK Client with lifecycle tracking.
 *
 * @property serverConfig The full LocalMCPServer configuration if available
 * @property processStatus The last known process status from the ProcessManager
 * @property sdkClient The MCP SDK Client instance
 * @property connectedAt When the client was connected
 * @property lastPing Result of the last ping check
 */
private data class MCPClientInternal(
    val serverConfig: LocalMCPServer,
    val processStatus: ProcessStatus,
    val sdkClient: Client,
    val connectedAt: Instant,
    val lastPing: Boolean
)
