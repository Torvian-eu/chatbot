package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Default [WorkerMcpClientService] implementation backed by MCP SDK client + stdio transport.
 *
 * @property processManager Process manager for local MCP process lifecycle.
 * @property ioDispatcher Dispatcher used for potentially blocking SDK and process operations.
 * @property serviceScope Coroutine scope used for inactivity auto-stop timers.
 * @property clock Clock source used for connection and activity timestamps.
 */
class WorkerMcpClientServiceImpl(
    private val processManager: WorkerLocalMcpProcessManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: Clock = Clock.System
) : WorkerMcpClientService {
    companion object {
        /**
         * Logger used for worker MCP client lifecycle and call diagnostics.
         */
        private val logger: Logger = LogManager.getLogger(WorkerMcpClientServiceImpl::class.java)
    }

    /**
     * Per-server connected SDK client registry.
     */
    private val clientByServerId: ConcurrentHashMap<Long, Client> = ConcurrentHashMap()

    /**
     * Per-server connected client metadata registry.
     */
    private val connectionStatusByServerId: ConcurrentHashMap<Long, WorkerMcpClientConnectionStatus> =
        ConcurrentHashMap()

    /**
     * Per-server runtime configuration registry for auto-stop behavior.
     */
    private val configByServerId: ConcurrentHashMap<Long, LocalMCPServerDto> = ConcurrentHashMap()

    /**
     * Per-server inactivity timer registry.
     */
    private val autoStopTimerJobByServerId: ConcurrentHashMap<Long, Job> = ConcurrentHashMap()

    /**
     * Base client-name prefix used for SDK client identity.
     */
    private val clientNamePrefix: String = "chatbot-worker-mcp-client"

    /**
     * Static SDK client version string.
     */
    private val clientVersion: String = "1.0.0"

    /**
     * Upper bound for one SDK connect attempt.
     */
    private val connectTimeoutSeconds: Int = 30

    /**
     * Request timeout used for list-tools calls.
     */
    private val requestTimeoutSeconds: Int = 120

    override suspend fun startAndConnect(config: LocalMCPServerDto): Either<WorkerMcpClientStartError, Unit> {
        val serverId = config.id
        logger.info("Starting MCP client connection for serverId={} name='{}'", serverId, config.name)
        if (clientByServerId.containsKey(serverId)) {
            logger.warn("MCP client is already connected for serverId={}", serverId)
            return WorkerMcpClientStartError.AlreadyConnected(serverId).left()
        }

        processManager.startServer(config).fold(
            ifLeft = { startError ->
                logger.error("Failed to start MCP process for serverId={}: {}", serverId, startError.message)
                return WorkerMcpClientStartError.ProcessStartFailed(
                    serverId = serverId,
                    reason = startError.message,
                    cause = startError.cause
                ).left()
            },
            ifRight = {}
        )

        val processInput = processManager.getProcessInputStream(serverId)
        val processOutput = processManager.getProcessOutputStream(serverId)
        if (processInput == null || processOutput == null) {
            logger.error("MCP process streams unavailable for serverId={}", serverId)
            processManager.stopServer(serverId)
            return WorkerMcpClientStartError.StreamsUnavailable(
                serverId = serverId,
                reason = "Process streams are unavailable"
            ).left()
        }

        return try {
            val client = Client(
                clientInfo = Implementation(
                    name = buildClientName(config),
                    version = clientVersion
                )
            )
            val transport = StdioClientTransport(
                input = processInput,
                output = processOutput,
                error = processManager.getProcessErrorStream(serverId)
            )
            logger.debug("Connecting MCP SDK client for serverId={} with timeoutSeconds={}", serverId, connectTimeoutSeconds)
            withContext(ioDispatcher) {
                withTimeout(connectTimeoutSeconds * 1000L) {
                    client.connect(transport)
                }
            }
            clientByServerId[serverId] = client
            val now = clock.now()
            connectionStatusByServerId[serverId] = WorkerMcpClientConnectionStatus(
                connectedAt = now,
                lastActivityAt = now
            )
            configByServerId[serverId] = config
            startOrResetAutoStopTimer(serverId)
            logger.info("Connected MCP client for serverId={}", serverId)
            Unit.right()
        } catch (_: TimeoutCancellationException) {
            logger.error("MCP client connection timed out for serverId={} after {} seconds", serverId, connectTimeoutSeconds)
            processManager.stopServer(serverId)
            WorkerMcpClientStartError.ConnectionFailed(
                serverId = serverId,
                reason = "Connection timed out after $connectTimeoutSeconds seconds"
            ).left()
        } catch (exception: Exception) {
            logger.error("Failed to connect MCP client for serverId={}: {}", serverId, exception.message, exception)
            processManager.stopServer(serverId)
            WorkerMcpClientStartError.ConnectionFailed(
                serverId = serverId,
                reason = exception.message ?: "Unknown error",
                cause = exception
            ).left()
        }
    }

    override suspend fun stopServer(serverId: Long): Either<WorkerMcpClientStopError, Unit> =
        stopServerInternal(serverId = serverId, fromTimer = false)

    /**
     * Stops one tracked MCP client/process pair.
     *
     * @param serverId Persisted local MCP server identifier.
     * @param fromTimer Indicates whether the stop was triggered by inactivity timer expiration.
     * @return Either stop error or Unit.
     */
    private suspend fun stopServerInternal(
        serverId: Long,
        fromTimer: Boolean
    ): Either<WorkerMcpClientStopError, Unit> {
        logger.info(
            "Stopping MCP client for serverId={} trigger={}",
            serverId,
            if (fromTimer) "inactivity-timer" else "manual"
        )
        val client = clientByServerId.remove(serverId)
            ?: run {
                logger.warn("Cannot stop MCP client because it is not connected for serverId={}", serverId)
                return WorkerMcpClientStopError.NotConnected(serverId).left()
            }
        connectionStatusByServerId.remove(serverId)
        configByServerId.remove(serverId)
        if (!fromTimer) {
            autoStopTimerJobByServerId.remove(serverId)?.cancel()
        } else {
            autoStopTimerJobByServerId.remove(serverId)
        }

        try {
            withContext(ioDispatcher) {
                client.close()
            }
            logger.debug("Closed MCP SDK client transport for serverId={}", serverId)
        } catch (exception: Exception) {
            logger.error("Failed to close MCP SDK client for serverId={}: {}", serverId, exception.message, exception)
            processManager.stopServer(serverId)
            return WorkerMcpClientStopError.DisconnectFailed(
                serverId = serverId,
                reason = exception.message ?: "Unknown error",
                cause = exception
            ).left()
        }

        processManager.stopServer(serverId).fold(
            ifLeft = { stopError ->
                if (stopError !is WorkerLocalMcpStopProcessError.ProcessNotRunning) {
                    logger.error("Failed to stop MCP process for serverId={}: {}", serverId, stopError.message)
                    return WorkerMcpClientStopError.ProcessStopFailed(
                        serverId = serverId,
                        reason = stopError.message,
                        cause = stopError.cause
                    ).left()
                }
                logger.debug("MCP process already stopped for serverId={}", serverId)
            },
            ifRight = {}
        )

        logger.info("Stopped MCP client/process for serverId={}", serverId)
        return Unit.right()
    }

    /**
     * @param serverId Persisted local MCP server identifier.
     * @return Either discovery error or discovered tool list.
     */
    override suspend fun discoverTools(serverId: Long): Either<WorkerMcpClientDiscoverToolsError, List<Tool>> {
        logger.debug("Discovering MCP tools for serverId={}", serverId)
        val client = clientByServerId[serverId]
            ?: run {
                logger.warn("Cannot discover tools because MCP client is not connected for serverId={}", serverId)
                return WorkerMcpClientDiscoverToolsError.NotConnected(serverId).left()
            }

        return try {
            val listToolsResult = withContext(ioDispatcher) {
                client.listTools(
                    request = ListToolsRequest(),
                    options = RequestOptions(timeout = requestTimeoutSeconds.seconds)
                )
            }
            updateActivity(serverId)
            logger.info("Discovered {} MCP tools for serverId={}", listToolsResult.tools.size, serverId)
            listToolsResult.tools.right()
        } catch (exception: Exception) {
            logger.error("Failed to discover MCP tools for serverId={}: {}", serverId, exception.message, exception)
            WorkerMcpClientDiscoverToolsError.ListToolsFailed(
                serverId = serverId,
                reason = exception.message ?: "Unknown error",
                cause = exception
            ).left()
        }
    }

    override fun isClientConnected(serverId: Long): Boolean = clientByServerId.containsKey(serverId)


    override fun getConnectionStatus(serverId: Long): WorkerMcpClientConnectionStatus? =
        connectionStatusByServerId[serverId]

    /**
     * @param serverId Persisted local MCP server identifier.
     * @return Either ping error or Unit when the client responds.
     */
    override suspend fun pingClient(serverId: Long): Either<WorkerMcpClientPingError, Unit> {
        logger.debug("Pinging MCP client for serverId={}", serverId)
        val client = clientByServerId[serverId]
            ?: run {
                logger.warn("Cannot ping because MCP client is not connected for serverId={}", serverId)
                return WorkerMcpClientPingError.NotConnected(serverId).left()
            }

        return try {
            withContext(ioDispatcher) {
                client.ping()
            }
            updateActivity(serverId)
            logger.debug("MCP ping succeeded for serverId={}", serverId)
            Unit.right()
        } catch (exception: Exception) {
            logger.warn("MCP ping failed for serverId={}: {}", serverId, exception.message)
            WorkerMcpClientPingError.PingFailed(
                serverId = serverId,
                reason = exception.message ?: "Unknown error",
                cause = exception
            ).left()
        }
    }

    /**
     * @param serverId Persisted local MCP server identifier.
     * @param toolName MCP tool name to invoke.
     * @param arguments JSON argument object passed to the tool.
     * @return Either call failure or MCP SDK call result.
     */
    override suspend fun callTool(
        serverId: Long,
        toolName: String,
        arguments: JsonObject
    ): Either<WorkerMcpClientCallToolError, CallToolResult> {
        logger.info("Calling MCP tool '{}' on serverId={}", toolName, serverId)
        val client = clientByServerId[serverId]
            ?: run {
                logger.warn("Cannot call MCP tool '{}' because client is not connected for serverId={}", toolName, serverId)
                return WorkerMcpClientCallToolError.NotConnected(serverId = serverId, toolName = toolName).left()
            }

        return try {
            val result = withContext(ioDispatcher) {
                client.callTool(
                    request = CallToolRequest(
                        params = CallToolRequestParams(
                            name = toolName,
                            arguments = arguments
                        )
                    ),
                    options = RequestOptions(timeout = requestTimeoutSeconds.seconds)
                )
            }
            updateActivity(serverId)
            logger.info("MCP tool '{}' completed for serverId={} isError={}", toolName, serverId, result.isError)
            result.right()
        } catch (exception: Exception) {
            logger.error(
                "MCP tool '{}' failed for serverId={}: {}",
                toolName,
                serverId,
                exception.message,
                exception
            )
            WorkerMcpClientCallToolError.ToolCallFailed(
                serverId = serverId,
                toolName = toolName,
                reason = exception.message ?: "Unknown error",
                cause = exception
            ).left()
        }
    }


    override suspend fun close() {
        logger.info("Closing WorkerMcpClientServiceImpl and disconnecting all managed MCP clients")
        val serverIds = clientByServerId.keys.toList()
        serverIds.forEach { serverId ->
            stopServer(serverId)
        }
        autoStopTimerJobByServerId.values.forEach { it.cancel() }
        autoStopTimerJobByServerId.clear()
        configByServerId.clear()
        connectionStatusByServerId.clear()
        processManager.close()
        serviceScope.coroutineContext[Job]?.cancel()
        logger.info("WorkerMcpClientServiceImpl closed")
    }

    /**
     * Starts or resets the inactivity auto-stop timer for one connected server.
     *
     * @param serverId Persisted local MCP server identifier.
     */
    private fun startOrResetAutoStopTimer(serverId: Long) {
        val config = configByServerId[serverId] ?: return

        autoStopTimerJobByServerId.remove(serverId)?.cancel()
        if (config.neverAutoStop) {
            logger.debug("Auto-stop timer disabled for serverId={} because neverAutoStop=true", serverId)
            return
        }

        val autoStopSeconds = config.effectiveAutoStopSeconds
        logger.debug("Scheduling inactivity auto-stop for serverId={} in {} seconds", serverId, autoStopSeconds)
        val timerJob = serviceScope.launch {
            try {
                delay(autoStopSeconds * 1000L)

                // Timer callbacks can race with manual stop calls; ignore already-disconnected cases.
                stopServerInternal(serverId = serverId, fromTimer = true)
            } catch (_: Exception) {
                // Timer cancellation during reconnect/stop is expected and intentionally ignored.
                logger.debug("Inactivity auto-stop timer cancelled for serverId={}", serverId)
            }
        }
        autoStopTimerJobByServerId[serverId] = timerJob
    }

    /**
     * Updates the last-activity timestamp and resets inactivity auto-stop timer.
     *
     * @param serverId Persisted local MCP server identifier.
     */
    private fun updateActivity(serverId: Long) {
        connectionStatusByServerId[serverId]?.let { currentStatus ->
            connectionStatusByServerId[serverId] = currentStatus.copy(lastActivityAt = clock.now())
        }
        startOrResetAutoStopTimer(serverId)
    }

    /**
     * Builds a stable SDK client name for one server configuration.
     *
     * @param config Resolved local MCP server configuration.
     * @return Sanitized SDK client name.
     */
    private fun buildClientName(config: LocalMCPServerDto): String {
        val normalizedName = config.name
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .ifBlank { "server" }
        return "$clientNamePrefix-$normalizedName-${config.id}"
    }
}



