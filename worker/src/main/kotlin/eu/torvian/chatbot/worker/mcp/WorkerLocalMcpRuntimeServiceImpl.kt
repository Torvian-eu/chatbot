package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * Default [WorkerLocalMcpRuntimeService] implementation backed by config-store and MCP runtime services.
 *
 * @property configStore Worker-local assigned MCP server config store.
 * @property clientService MCP SDK/process runtime service.
 */
class WorkerLocalMcpRuntimeServiceImpl(
    private val configStore: WorkerLocalMcpServerConfigStore,
    private val clientService: WorkerMcpClientService
) : WorkerLocalMcpRuntimeService {
    /**
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or Unit.
     */
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

    /**
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or Unit.
     */
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

    /**
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or test outcome.
     */
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

    /**
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or minimal refresh outcome.
     */
    override suspend fun refreshTools(
        serverId: Long
    ): Either<WorkerLocalMcpRuntimeError, WorkerLocalMcpRefreshToolsOutcome> {
        configStore.getServer(serverId)
            ?: return WorkerLocalMcpRuntimeError.ServerConfigMissing(serverId).left()

        // TODO: Keep refresh runtime-minimal in worker because persisted diff ownership is server-side.
        return WorkerLocalMcpRefreshToolsOutcome(
            addedTools = emptyList(),
            updatedTools = emptyList(),
            deletedTools = emptyList()
        ).right()
    }
}



