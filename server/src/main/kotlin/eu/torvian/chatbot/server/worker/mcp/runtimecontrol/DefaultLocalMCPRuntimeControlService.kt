package eu.torvian.chatbot.server.worker.mcp.runtimecontrol

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerConnectionResponse
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpDiscoveredToolData
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.LocalMCPToolDefinitionService
import eu.torvian.chatbot.server.service.core.error.mcp.RefreshMCPToolsError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerNotFoundError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerServiceError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerUnauthorizedError
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Clock

/**
 * Worker-backed runtime-control implementation that preserves existing route contracts.
 *
 * This service validates ownership through [eu.torvian.chatbot.server.service.core.LocalMCPServerService], resolves the assigned worker,
 * and dispatches runtime-control commands through [LocalMCPRuntimeCommandDispatchService].
 *
 * @property localMCPServerService Service used for ownership checks and server lookup.
 * @property localMCPRuntimeCommandDispatchService Typed worker command adapter.
 * @property localMCPToolDefinitionService Service used for persisted MCP tool refresh orchestration.
 */
class DefaultLocalMCPRuntimeControlService(
    private val localMCPServerService: LocalMCPServerService,
    private val localMCPRuntimeCommandDispatchService: LocalMCPRuntimeCommandDispatchService,
    private val localMCPToolDefinitionService: LocalMCPToolDefinitionService
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
        val discoveredTools = withError({ it.toRuntimeControlError(serverId) }) {
            localMCPRuntimeCommandDispatchService.discoverTools(workerId = server.workerId, serverId = server.id).bind()
        }
        val currentTools = discoveredTools.tools.map { discoveredTool ->
            discoveredTool.toPersistedToolDefinition(server)
        }

        val result = withError({ it.toRuntimeControlError(serverId) }) {
            localMCPToolDefinitionService.refreshMCPTools(
                serverId = server.id,
                currentTools = currentTools
            ).bind()
        }

        RefreshMCPToolsResponse(
            addedTools = result.addedTools,
            updatedTools = result.updatedTools,
            deletedTools = result.deletedTools
        )
    }

    /**
     * Maps worker discovery metadata into a persisted local MCP tool definition candidate.
     *
     * Prefixing and persisted-tool shaping happen on the server so worker discovery remains runtime-only.
     *
     * @receiver Discovered worker tool metadata.
     * @param server Owning server configuration used for naming/persistence defaults.
     * @return Server-shaped tool definition ready for differential refresh.
     */
    private fun WorkerMcpDiscoveredToolData.toPersistedToolDefinition(
        server: LocalMCPServerDto
    ): LocalMCPToolDefinition {
        val now = Clock.System.now()
        return LocalMCPToolDefinition(
            id = 0L,
            name = buildToolName(server.toolNamePrefix, name),
            description = description.orEmpty(),
            config = buildJsonObject { },
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            isEnabled = true,
            createdAt = now,
            updatedAt = now,
            serverId = server.id,
            mcpToolName = name
        )
    }

    /**
     * Constructs the persisted tool name shown to LLMs by prepending [prefix] to [mcpToolName].
     *
     * @param prefix Optional server-configured tool prefix.
     * @param mcpToolName Raw MCP tool name returned by runtime discovery.
     * @return Prefixed name when [prefix] is not blank; otherwise [mcpToolName].
     */
    private fun buildToolName(prefix: String?, mcpToolName: String): String =
        if (prefix.isNullOrBlank()) mcpToolName else "$prefix$mcpToolName"

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

    /**
     * Maps Local MCP tool refresh service errors into runtime-control API errors.
     *
     * @receiver Tool-refresh service error.
     * @param serverId Local MCP server identifier involved in refresh orchestration.
     * @return Runtime-control error for route-level mapping.
     */
    private fun RefreshMCPToolsError.toRuntimeControlError(serverId: Long): LocalMCPRuntimeControlError = when (this) {
        is RefreshMCPToolsError.ServerNotFound -> LocalMCPRuntimeControlError.ServerNotFoundError(serverId)
        is RefreshMCPToolsError.DuplicateName -> {
            LocalMCPRuntimeControlError.InternalError(
                "Tool refresh produced duplicate tool name '$name'"
            )
        }

        is RefreshMCPToolsError.ToolValidationError -> {
            LocalMCPRuntimeControlError.InternalError(
                "Tool refresh validation failed: $validationError"
            )
        }

        is RefreshMCPToolsError.OtherError -> LocalMCPRuntimeControlError.InternalError(message)
    }
}