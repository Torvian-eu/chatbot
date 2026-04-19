package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Default [WorkerMcpClientService] implementation backed by MCP SDK client + stdio transport.
 *
 * @property processManager Process manager for local MCP process lifecycle.
 * @property ioDispatcher Dispatcher used for potentially blocking SDK and process operations.
 */
class WorkerMcpClientServiceImpl(
    private val processManager: WorkerLocalMcpProcessManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : WorkerMcpClientService {
    /**
     * Per-server connected SDK client registry.
     */
    private val clientByServerId: ConcurrentHashMap<Long, Client> = ConcurrentHashMap()

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

    /**
     * @param config Resolved local MCP server configuration.
     * @return Either start/connect error or Unit.
     */
    override suspend fun startAndConnect(config: LocalMCPServerDto): Either<WorkerMcpClientStartError, Unit> {
        val serverId = config.id
        if (clientByServerId.containsKey(serverId)) {
            return WorkerMcpClientStartError.AlreadyConnected(serverId).left()
        }

        processManager.startServer(config).fold(
            ifLeft = { startError ->
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
            withContext(ioDispatcher) {
                withTimeout(connectTimeoutSeconds * 1000L) {
                    client.connect(transport)
                }
            }
            clientByServerId[serverId] = client
            Unit.right()
        } catch (_: TimeoutCancellationException) {
            processManager.stopServer(serverId)
            WorkerMcpClientStartError.ConnectionFailed(
                serverId = serverId,
                reason = "Connection timed out after $connectTimeoutSeconds seconds"
            ).left()
        } catch (exception: Exception) {
            processManager.stopServer(serverId)
            WorkerMcpClientStartError.ConnectionFailed(
                serverId = serverId,
                reason = exception.message ?: "Unknown error",
                cause = exception
            ).left()
        }
    }

    /**
     * @param serverId Persisted local MCP server identifier.
     * @return Either stop error or Unit.
     */
    override suspend fun stopServer(serverId: Long): Either<WorkerMcpClientStopError, Unit> {
        val client = clientByServerId.remove(serverId)
        if (client == null) {
            return WorkerMcpClientStopError.NotConnected(serverId).left()
        }

        try {
            withContext(ioDispatcher) {
                client.close()
            }
        } catch (exception: Exception) {
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
                    return WorkerMcpClientStopError.ProcessStopFailed(
                        serverId = serverId,
                        reason = stopError.message,
                        cause = stopError.cause
                    ).left()
                }
            },
            ifRight = {}
        )

        return Unit.right()
    }

    /**
     * @param serverId Persisted local MCP server identifier.
     * @return Either discovery error or discovered tool list.
     */
    override suspend fun discoverTools(serverId: Long): Either<WorkerMcpClientDiscoverToolsError, List<Tool>> {
        val client = clientByServerId[serverId]
            ?: return WorkerMcpClientDiscoverToolsError.NotConnected(serverId).left()

        return try {
            val listToolsResult = withContext(ioDispatcher) {
                client.listTools(
                    request = ListToolsRequest(),
                    options = RequestOptions(timeout = requestTimeoutSeconds.seconds)
                )
            }
            listToolsResult.tools.right()
        } catch (exception: Exception) {
            WorkerMcpClientDiscoverToolsError.ListToolsFailed(
                serverId = serverId,
                reason = exception.message ?: "Unknown error",
                cause = exception
            ).left()
        }
    }

    /**
     * @param serverId Persisted local MCP server identifier.
     * @return True when a client is currently registered.
     */
    override fun isClientConnected(serverId: Long): Boolean = clientByServerId.containsKey(serverId)

    /**
     * Stops all tracked clients and delegates final cleanup to process manager.
     */
    override suspend fun close() {
        val serverIds = clientByServerId.keys.toList()
        serverIds.forEach { serverId ->
            stopServer(serverId)
        }
        processManager.close()
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



