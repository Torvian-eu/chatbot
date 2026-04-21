package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStateDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Default [WorkerLocalMcpRuntimeService] implementation backed by config-store and MCP runtime services.
 *
 * @property configStore Worker-local assigned MCP server config store.
 * @property clientService MCP SDK/process runtime service.
 * @property processManager MCP process runtime status provider.
 */
class WorkerLocalMcpRuntimeServiceImpl(
    private val configStore: WorkerLocalMcpServerConfigStore,
    private val clientService: WorkerMcpClientService,
    private val processManager: WorkerLocalMcpProcessManager
) : WorkerLocalMcpRuntimeService {

    override suspend fun startServer(serverId: Long): Either<WorkerLocalMcpRuntimeError, Unit> {
        val config = configStore.getServer(serverId)
            ?: return WorkerLocalMcpRuntimeError.ServerConfigMissing(serverId).left()

        return clientService.startAndConnect(config).fold(
            ifLeft = { startError ->
                when (startError) {
                    is WorkerMcpClientStartError.AlreadyConnected -> Unit.right()
                    is WorkerMcpClientStartError.ConnectionFailed -> WorkerLocalMcpRuntimeError.StartFailed(
                        serverId = serverId,
                        message = startError.message,
                        details = startError.cause?.message
                    ).left()

                    is WorkerMcpClientStartError.ProcessStartFailed -> WorkerLocalMcpRuntimeError.StartFailed(
                        serverId = serverId,
                        message = startError.message,
                        details = startError.cause?.message
                    ).left()

                    is WorkerMcpClientStartError.StreamsUnavailable -> WorkerLocalMcpRuntimeError.StartFailed(
                        serverId = serverId,
                        message = startError.message,
                        details = startError.reason
                    ).left()
                }
            },
            ifRight = { Unit.right() }
        )
    }

    override suspend fun stopServer(serverId: Long): Either<WorkerLocalMcpRuntimeError, Unit> {
        configStore.getServer(serverId)
            ?: return WorkerLocalMcpRuntimeError.ServerConfigMissing(serverId).left()

        return clientService.stopServer(serverId).fold(
            ifLeft = { stopError ->
                when (stopError) {
                    is WorkerMcpClientStopError.NotConnected -> Unit.right()
                    is WorkerMcpClientStopError.DisconnectFailed -> WorkerLocalMcpRuntimeError.StopFailed(
                        serverId = serverId,
                        message = stopError.message,
                        details = stopError.cause?.message
                    ).left()

                    is WorkerMcpClientStopError.ProcessStopFailed -> WorkerLocalMcpRuntimeError.StopFailed(
                        serverId = serverId,
                        message = stopError.message,
                        details = stopError.cause?.message
                    ).left()
                }
            },
            ifRight = { Unit.right() }
        )
    }

    override suspend fun testConnection(
        serverId: Long
    ): Either<WorkerLocalMcpRuntimeError, WorkerLocalMcpTestConnectionOutcome> {
        val config = configStore.getServer(serverId)
            ?: return WorkerLocalMcpRuntimeError.ServerConfigMissing(serverId).left()

        val wasConnected = clientService.isClientConnected(serverId)
        var startedByTest = false

        if (!wasConnected) {
            val startResult = clientService.startAndConnect(config)
            startResult.fold(
                ifLeft = { startError ->
                    if (startError !is WorkerMcpClientStartError.AlreadyConnected) {
                        return WorkerLocalMcpRuntimeError.StartFailed(
                            serverId = serverId,
                            message = startError.message,
                            details = startError.cause?.message
                        ).left()
                    }
                },
                ifRight = { startedByTest = true }
            )
        }

        val toolCount = clientService.discoverTools(serverId).fold(
            ifLeft = { discoverError ->
                return WorkerLocalMcpRuntimeError.DiscoveryFailed(
                    serverId = serverId,
                    message = discoverError.message,
                    details = discoverError.cause?.message
                ).left()
            },
            ifRight = { tools -> tools.size }
        )

        if (startedByTest) {
            clientService.stopServer(serverId).fold(ifLeft = { stopError ->
                return WorkerLocalMcpRuntimeError.CleanupFailed(
                    serverId = serverId,
                    message = stopError.message,
                    details = stopError.cause?.message
                ).left()
            }, ifRight = { Unit })
        }

        return WorkerLocalMcpTestConnectionOutcome(
            discoveredToolCount = toolCount,
            message = "Connection test succeeded"
        ).right()
    }

    override suspend fun discoverTools(
        serverId: Long
    ): Either<WorkerLocalMcpRuntimeError, List<WorkerLocalMcpDiscoveredTool>> {
        val config = configStore.getServer(serverId)
            ?: return WorkerLocalMcpRuntimeError.ServerConfigMissing(serverId).left()

        val wasConnected = clientService.isClientConnected(serverId)
        var startedByDiscovery = false

        if (!wasConnected) {
            val startResult = clientService.startAndConnect(config)
            startResult.fold(
                ifLeft = { startError ->
                    if (startError !is WorkerMcpClientStartError.AlreadyConnected) {
                        return WorkerLocalMcpRuntimeError.StartFailed(
                            serverId = serverId,
                            message = startError.message,
                            details = startError.cause?.message
                        ).left()
                    }
                },
                ifRight = { startedByDiscovery = true }
            )
        }

        var cleanupError: WorkerLocalMcpRuntimeError? = null

        val discoveryResult = try {
            clientService.discoverTools(serverId).fold(
                ifLeft = { discoverError ->
                    WorkerLocalMcpRuntimeError.DiscoveryFailed(
                        serverId = serverId,
                        message = discoverError.message,
                        details = discoverError.cause?.message
                    ).left()
                },
                ifRight = { tools -> tools.map { it.toDiscoveredTool() }.right() }
            )
        } finally {
            if (startedByDiscovery) {
                clientService.stopServer(serverId).fold(
                    ifLeft = { stopError ->
                        cleanupError = WorkerLocalMcpRuntimeError.CleanupFailed(
                            serverId = serverId,
                            message = stopError.message,
                            details = stopError.cause?.message
                        )
                    },
                    ifRight = { Unit }
                )
            }
        }

        cleanupError?.let { return it.left() }
        return discoveryResult
    }

    override suspend fun getRuntimeStatus(serverId: Long): Either<WorkerLocalMcpRuntimeError, LocalMcpServerRuntimeStatusDto> {
        configStore.getServer(serverId)
            ?: return WorkerLocalMcpRuntimeError.ServerConfigMissing(serverId).left()

        return buildRuntimeStatus(serverId).right()
    }

    override suspend fun listRuntimeStatuses(): List<LocalMcpServerRuntimeStatusDto> {
        return configStore.listServers().map { server ->
            buildRuntimeStatus(server.id)
        }
    }

    override suspend fun callTool(
        serverId: Long,
        toolName: String,
        arguments: JsonObject
    ): Either<WorkerLocalMcpRuntimeError, WorkerMcpToolCallOutcome?> {
        val config = configStore.getServer(serverId)
            ?: return WorkerLocalMcpRuntimeError.ServerConfigMissing(serverId).left()

        // Ensure client is connected, starting it if necessary.
        val wasConnected = clientService.isClientConnected(serverId)
        if (!wasConnected) {
            val startResult = clientService.startAndConnect(config)
            startResult.fold(
                ifLeft = { startError ->
                    if (startError !is WorkerMcpClientStartError.AlreadyConnected) {
                        return WorkerLocalMcpRuntimeError.StartFailed(
                            serverId = serverId,
                            message = startError.message,
                            details = startError.cause?.message
                        ).left()
                    }
                },
                ifRight = {}
            )
        }

        // Call the tool through the client service.
        return clientService.callTool(serverId, toolName, arguments).fold(
            ifLeft = { callError ->
                WorkerLocalMcpRuntimeError.ToolCallFailed(
                    serverId = serverId,
                    toolName = toolName,
                    message = callError.message,
                    details = callError.cause?.message
                ).left()
            },
            ifRight = { callToolResult ->
                // Map SDK CallToolResult into runtime-level outcome.
                // Extract first text content from the result.
                val textContent = callToolResult.content
                    .filterIsInstance<TextContent>()
                    .firstOrNull()
                    ?.text

                WorkerMcpToolCallOutcome(
                    isError = callToolResult.isError ?: false,
                    structuredContent = if (callToolResult.isError == true) textContent else null,
                    textContent = if (callToolResult.isError != true) textContent else null
                ).right()
            }
        )
    }

    /**
     * Maps one MCP SDK [Tool] into worker runtime discovery metadata.
     *
     * @receiver MCP SDK tool from `listTools`.
     * @return Discovery metadata that can be encoded into worker protocol DTOs.
     */
    private fun Tool.toDiscoveredTool(): WorkerLocalMcpDiscoveredTool {
        return WorkerLocalMcpDiscoveredTool(
            name = name,
            description = description,
            inputSchema = Json.encodeToJsonElement(inputSchema) as JsonObject,
            outputSchema = outputSchema?.let { Json.encodeToJsonElement(it) as JsonObject }
        )
    }

    /**
     * Builds the shared runtime-status DTO from process and connection snapshots.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Shared runtime-status DTO for app/server read models.
     */
    private suspend fun buildRuntimeStatus(serverId: Long): LocalMcpServerRuntimeStatusDto {
        val processStatus = processManager.getServerStatus(serverId)
        val connectionStatus = clientService.getConnectionStatus(serverId)

        return LocalMcpServerRuntimeStatusDto(
            serverId = serverId,
            state = processStatus.state.toRuntimeStateDto(),
            pid = processStatus.pid,
            startedAt = processStatus.startedAt,
            exitCode = processStatus.exitCode,
            stoppedAt = processStatus.stoppedAt,
            errorMessage = processStatus.errorMessage,
            connectedAt = connectionStatus?.connectedAt,
            lastActivityAt = connectionStatus?.lastActivityAt
        )
    }

    /**
     * Maps worker-local process state to shared runtime-state DTO enum.
     *
     * @receiver Worker-local process lifecycle state.
     * @return Shared runtime-state enum value.
     */
    private fun WorkerLocalMcpProcessState.toRuntimeStateDto(): LocalMcpServerRuntimeStateDto = when (this) {
        WorkerLocalMcpProcessState.RUNNING -> LocalMcpServerRuntimeStateDto.RUNNING
        WorkerLocalMcpProcessState.STOPPED -> LocalMcpServerRuntimeStateDto.STOPPED
        WorkerLocalMcpProcessState.ERROR -> LocalMcpServerRuntimeStateDto.ERROR
    }
}



