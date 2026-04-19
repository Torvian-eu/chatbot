package eu.torvian.chatbot.server.worker.mcp.runtimecontrol

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerConnectionResponse
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerNotFoundError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerServiceError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerUnauthorizedError
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError

/**
 * Worker-backed runtime-control implementation that preserves existing route contracts.
 *
 * This service validates ownership through [eu.torvian.chatbot.server.service.core.LocalMCPServerService], resolves the assigned worker,
 * and dispatches runtime-control commands through [LocalMCPRuntimeCommandDispatchService].
 *
 * @property localMCPServerService Service used for ownership checks and server lookup.
 * @property localMCPRuntimeCommandDispatchService Typed worker command adapter.
 */
class DefaultLocalMCPRuntimeControlService(
    private val localMCPServerService: LocalMCPServerService,
    private val localMCPRuntimeCommandDispatchService: LocalMCPRuntimeCommandDispatchService
) : LocalMCPRuntimeControlService {
    override suspend fun startServer(userId: Long, serverId: Long): Either<LocalMCPRuntimeControlError, Unit> = either {
        val server = resolveOwnedServer(userId = userId, serverId = serverId).bind()
        withError({ it.toRuntimeControlError(serverId) }) {
            localMCPRuntimeCommandDispatchService.startServer(workerId = server.workerId, serverId = server.id).bind()
        }
    }

    override suspend fun stopServer(userId: Long, serverId: Long): Either<LocalMCPRuntimeControlError, Unit> = either {
        val server = resolveOwnedServer(userId = userId, serverId = serverId).bind()
        withError({ it.toRuntimeControlError(serverId) }) {
            localMCPRuntimeCommandDispatchService.stopServer(workerId = server.workerId, serverId = server.id).bind()
        }
    }

    override suspend fun testConnection(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeControlError, TestLocalMCPServerConnectionResponse> = either {
        val server = resolveOwnedServer(userId = userId, serverId = serverId).bind()
        val result = withError({ it.toRuntimeControlError(serverId) }) {
            localMCPRuntimeCommandDispatchService.testConnection(workerId = server.workerId, serverId = server.id).bind()
        }

        TestLocalMCPServerConnectionResponse(
            serverId = result.serverId,
            success = result.success,
            discoveredToolCount = result.discoveredToolCount,
            message = result.message
        )
    }

    override suspend fun refreshTools(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeControlError, RefreshMCPToolsResponse> = either {
        val server = resolveOwnedServer(userId = userId, serverId = serverId).bind()
        val result = withError({ it.toRuntimeControlError(serverId) }) {
            localMCPRuntimeCommandDispatchService.refreshTools(workerId = server.workerId, serverId = server.id).bind()
        }

        RefreshMCPToolsResponse(
            addedTools = result.addedTools,
            updatedTools = result.updatedTools,
            deletedTools = result.deletedTools
        )
    }

    /**
     * Resolves and ownership-validates a Local MCP server.
     *
     * @param userId Authenticated user identifier.
     * @param serverId Local MCP server identifier.
     * @return Either runtime-control error or owned server DTO.
     */
    private suspend fun resolveOwnedServer(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeControlError, LocalMCPServerDto> =
        either {
            withError({ it.toRuntimeControlError() }) {
                localMCPServerService.getServerById(userId = userId, serverId = serverId).bind()
            }
        }

    /**
     * Maps Local MCP server service errors into runtime-control API errors.
     *
     * @receiver Upstream Local MCP server service error.
     * @return Runtime-control error for route-level mapping.
     */
    private fun LocalMCPServerServiceError.toRuntimeControlError(): LocalMCPRuntimeControlError = when (this) {
        is LocalMCPServerNotFoundError -> LocalMCPRuntimeControlError.ServerNotFoundError(serverId)
        is LocalMCPServerUnauthorizedError -> LocalMCPRuntimeControlError.UnauthorizedError(userId, serverId)
        else -> LocalMCPRuntimeControlError.InternalError("Failed to validate server ownership for runtime control")
    }

    /**
     * Maps worker runtime command orchestration errors into runtime-control API errors.
     *
     * @receiver Worker orchestration error.
     * @param serverId Local MCP server identifier involved in the operation.
     * @return Runtime-control error for route-level mapping.
     */
    private fun LocalMCPRuntimeCommandDispatchError.toRuntimeControlError(
        serverId: Long
    ): LocalMCPRuntimeControlError = when (this) {
        is LocalMCPRuntimeCommandDispatchError.DispatchFailed -> {
            when (error) {
                is WorkerCommandDispatchError.Rejected -> {
                    LocalMCPRuntimeControlError.RuntimeUnavailableError(
                        serverId,
                        "Worker rejected command: ${error.rejection.reasonCode} - ${error.rejection.message}"
                    )
                }

                is WorkerCommandDispatchError.WorkerNotConnected -> {
                    LocalMCPRuntimeControlError.RuntimeUnavailableError(serverId, "Assigned worker is not connected")
                }

                is WorkerCommandDispatchError.TimedOut -> {
                    LocalMCPRuntimeControlError.RuntimeUnavailableError(
                        serverId,
                        "Worker command timed out after ${error.timeout}"
                    )
                }

                is WorkerCommandDispatchError.SessionDisconnected -> {
                    LocalMCPRuntimeControlError.RuntimeUnavailableError(
                        serverId,
                        "Worker session disconnected${error.reason?.let { ": $it" } ?: ""}"
                    )
                }

                is WorkerCommandDispatchError.SendFailed -> {
                    LocalMCPRuntimeControlError.RuntimeUnavailableError(
                        serverId,
                        "Failed to send worker command: ${error.reason}"
                    )
                }

                is WorkerCommandDispatchError.MalformedLifecyclePayload -> {
                    LocalMCPRuntimeControlError.InternalError(
                        "Worker returned malformed lifecycle payload for ${error.commandType}/${error.messageType}: ${error.reason}"
                    )
                }

                is WorkerCommandDispatchError.DuplicateInteractionId -> {
                    LocalMCPRuntimeControlError.InternalError(
                        "Worker dispatch failed due to duplicate interaction '${error.interactionId}'"
                    )
                }
            }
        }

        is LocalMCPRuntimeCommandDispatchError.InvalidPayload -> {
            LocalMCPRuntimeControlError.InternalError(
                "Worker payload mapping failed for $commandType: $details"
            )
        }

        is LocalMCPRuntimeCommandDispatchError.CommandFailed -> {
            LocalMCPRuntimeControlError.RuntimeUnavailableError(
                serverId,
                "Worker command $commandType failed with code '$code': $message${details?.let { " ($it)" } ?: ""}"
            )
        }

        is LocalMCPRuntimeCommandDispatchError.UnexpectedResultStatus -> {
            LocalMCPRuntimeControlError.RuntimeUnavailableError(
                serverId,
                "Worker completed $commandType with unsupported status '$status'"
            )
        }
    }
}