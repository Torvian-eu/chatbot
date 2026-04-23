package eu.torvian.chatbot.server.worker.mcp.runtimecontrol

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.api.mcp.*
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpDiscoveredToolData
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.LocalMCPToolDefinitionService
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerNotFoundError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerServiceError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerUnauthorizedError
import eu.torvian.chatbot.server.service.core.error.mcp.RefreshMCPToolsError
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Clock

/**
 * Worker-backed runtime-control implementation for Local MCP server operations.
 *
 * The service resolves persisted servers through [LocalMCPServerService], dispatches runtime
 * commands through [LocalMCPRuntimeCommandDispatchService], and translates worker failures into
 * operator-facing runtime-control errors.
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
        withError({ it.toRuntimeControlError(serverId = server.id) }) {
            localMCPRuntimeCommandDispatchService.startServer(workerId = server.workerId, serverId = server.id).bind()
        }
    }

    override suspend fun stopServer(userId: Long, serverId: Long): Either<LocalMCPRuntimeControlError, Unit> = either {
        val server = resolveOwnedServer(userId = userId, serverId = serverId).bind()
        withError({ it.toRuntimeControlError(serverId = server.id) }) {
            localMCPRuntimeCommandDispatchService.stopServer(workerId = server.workerId, serverId = server.id).bind()
        }
    }

    override suspend fun testConnection(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeControlError, TestLocalMCPServerConnectionResponse> = either {
        val server = resolveOwnedServer(userId = userId, serverId = serverId).bind()
        val result = withError({ it.toRuntimeControlError(serverId = server.id) }) {
            localMCPRuntimeCommandDispatchService.testConnection(workerId = server.workerId, serverId = server.id)
                .bind()
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
        val discoveredTools = withError({ it.toRuntimeControlError(serverId = server.id) }) {
            localMCPRuntimeCommandDispatchService.discoverTools(workerId = server.workerId, serverId = server.id).bind()
        }
        val currentTools = discoveredTools.tools.map { it.toPersistedToolDefinition(server) }

        val result = withError({ it.toRuntimeControlError(serverId = server.id) }) {
            localMCPToolDefinitionService.refreshMCPTools(serverId = server.id, currentTools = currentTools).bind()
        }

        RefreshMCPToolsResponse(
            addedTools = result.addedTools,
            updatedTools = result.updatedTools,
            deletedTools = result.deletedTools
        )
    }

    override suspend fun getRuntimeStatus(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeControlError, LocalMcpServerRuntimeStatusDto> = either {
        val server = resolveOwnedServer(userId = userId, serverId = serverId).bind()

        localMCPRuntimeCommandDispatchService.getRuntimeStatus(workerId = server.workerId, serverId = server.id)
            .fold(
                ifLeft = { dispatchError ->
                    fallbackRuntimeStatus(serverId = server.id, reason = dispatchError.toRuntimeStatusFallbackReason())
                },
                ifRight = { response -> response.status }
            )
    }

    override suspend fun listRuntimeStatuses(
        userId: Long
    ): Either<LocalMCPRuntimeControlError, List<LocalMcpServerRuntimeStatusDto>> = either {
        val ownedServers = resolveOwnedServers(userId = userId).bind()
        val serverIds = ownedServers.map { it.id }.toSet()
        val statusesByServerId = mutableMapOf<Long, LocalMcpServerRuntimeStatusDto>()

        ownedServers.groupBy { it.workerId }.forEach { (workerId, serversForWorker) ->
            val workerStatuses = localMCPRuntimeCommandDispatchService.listRuntimeStatuses(workerId = workerId)
                .fold(
                    ifLeft = { dispatchError ->
                        serversForWorker.associate { server ->
                            server.id to fallbackRuntimeStatus(
                                serverId = server.id,
                                reason = dispatchError.toRuntimeStatusFallbackReason()
                            )
                        }
                    },
                    ifRight = { response -> response.statuses.associateBy { it.serverId } }
                )

            serversForWorker.forEach { server ->
                statusesByServerId[server.id] = workerStatuses[server.id]
                    ?: fallbackRuntimeStatus(
                        serverId = server.id,
                        reason = "Worker did not return runtime status for this server"
                    )
            }
        }

        ownedServers
            .mapNotNull { server -> statusesByServerId[server.id] }
            .filter { status -> status.serverId in serverIds }
    }

    override suspend fun testDraftConnection(
        userId: Long,
        request: TestLocalMCPServerDraftConnectionRequest
    ): Either<LocalMCPRuntimeControlError, TestLocalMCPServerConnectionResponse> = either {
        withError({ error: LocalMCPServerServiceError -> error.toRuntimeControlError() }) {
            localMCPServerService.validateWorkerOwnership(userId, request.workerId).bind()
        }

        val result = withError({ it.toRuntimeControlError(workerId = request.workerId) }) {
            localMCPRuntimeCommandDispatchService.testDraftConnection(workerId = request.workerId, request = request)
                .bind()
        }

        TestLocalMCPServerConnectionResponse(
            serverId = null,
            success = result.success,
            discoveredToolCount = result.discoveredToolCount,
            message = result.message
        )
    }

    /**
     * Transforms discovered worker-tool metadata into a persisted tool definition candidate.
     *
     * Prefixing is done on the server so discovery stays runtime-only in the worker process.
     *
     * @receiver Discovered worker tool metadata.
     * @param server Owning server configuration used for naming and persistence defaults.
     * @return Persisted tool definition ready for refresh comparison.
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
     * Prefixes a discovered MCP tool name when the server config defines one.
     *
     * @param prefix Optional server-configured tool prefix.
     * @param mcpToolName Raw MCP tool name returned by runtime discovery.
     * @return Prefixed name when [prefix] is not blank; otherwise [mcpToolName].
     */
    private fun buildToolName(prefix: String?, mcpToolName: String): String =
        if (prefix.isNullOrBlank()) mcpToolName else "$prefix$mcpToolName"

    /**
     * Resolves and ownership-validates a persisted Local MCP server.
     *
     * @param userId Authenticated user identifier.
     * @param serverId Persisted server identifier.
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
     * Resolves all Local MCP servers owned by one user.
     *
     * @param userId Authenticated user identifier.
     * @return Either runtime-control error or owned server DTO list.
     */
    private suspend fun resolveOwnedServers(
        userId: Long
    ): Either<LocalMCPRuntimeControlError, List<LocalMCPServerDto>> = either {
        withError({ LocalMCPRuntimeControlError.InternalError("Failed to list owned MCP servers for runtime status") }) {
            localMCPServerService.getServersByUserId(userId).bind()
        }
    }

    /**
     * Builds deterministic fallback runtime status for worker-dispatch failures.
     *
     * @param serverId Persisted local MCP server identifier.
     * @param reason Human-readable fallback reason.
     * @return Fallback runtime status DTO.
     */
    private fun fallbackRuntimeStatus(serverId: Long, reason: String): LocalMcpServerRuntimeStatusDto =
        LocalMcpServerRuntimeStatusDto(
            serverId = serverId,
            state = LocalMcpServerRuntimeStateDto.STOPPED,
            errorMessage = reason
        )

    /**
     * Maps worker runtime-status command failures into human-readable fallback reasons.
     *
     * @receiver Worker runtime command dispatch error.
     * @return Fallback reason string used when runtime status cannot be retrieved directly.
     */
    private fun LocalMCPRuntimeCommandDispatchError.toRuntimeStatusFallbackReason(): String = when (this) {
        is LocalMCPRuntimeCommandDispatchError.DispatchFailed -> when (error) {
            is WorkerCommandDispatchError.WorkerNotConnected -> "Assigned worker is not connected"
            is WorkerCommandDispatchError.TimedOut -> "Worker runtime status request timed out after ${error.timeout}"
            is WorkerCommandDispatchError.SessionDisconnected -> {
                "Worker session disconnected${error.reason?.let { ": $it" } ?: ""}"
            }

            is WorkerCommandDispatchError.Rejected -> {
                "Worker rejected runtime status command: ${error.rejection.reasonCode} - ${error.rejection.message}"
            }

            is WorkerCommandDispatchError.SendFailed -> "Failed to send runtime status command to worker: ${error.reason}"
            is WorkerCommandDispatchError.MalformedLifecyclePayload -> {
                "Worker returned malformed runtime status payload for ${error.commandType}/${error.messageType}"
            }

            is WorkerCommandDispatchError.DuplicateInteractionId -> {
                "Worker dispatch failed due to duplicate interaction '${error.interactionId}'"
            }
        }

        is LocalMCPRuntimeCommandDispatchError.CommandFailed -> {
            "Worker command $commandType failed with code '$code': $message${details?.let { " ($it)" } ?: ""}"
        }

        is LocalMCPRuntimeCommandDispatchError.InvalidPayload -> {
            "Runtime status payload mapping failed for $commandType: $details"
        }

        is LocalMCPRuntimeCommandDispatchError.UnexpectedResultStatus -> {
            "Worker completed $commandType with unsupported status '$status'"
        }
    }

    /**
     * Converts a worker dispatch failure into the appropriate runtime-control availability error.
     *
     * Draft requests are worker-scoped rather than server-scoped, so they surface the worker ID in
     * their error payload instead of fabricating a server ID.
     *
     * @receiver Worker dispatch error.
     * @param serverId Persisted server identifier when one exists.
     * @param workerId Worker identifier when the request is draft-scoped.
     * @param reason Human-readable failure reason.
     * @return Server-scoped or worker-scoped runtime-unavailable error.
     */
    private fun runtimeUnavailableError(
        serverId: Long?,
        workerId: Long?,
        reason: String
    ): LocalMCPRuntimeControlError =
        if (serverId != null) {
            LocalMCPRuntimeControlError.RuntimeUnavailableError(serverId, reason)
        } else {
            LocalMCPRuntimeControlError.DraftRuntimeUnavailableError(workerId ?: -1L, reason)
        }

    /**
     * Maps worker dispatch failures into runtime-control errors.
     *
     * @receiver Worker orchestration error.
     * @param serverId Persisted server identifier when one exists, or null for draft requests.
     * @param workerId Worker identifier when the request is draft-scoped.
     * @return Runtime-control error for route-level mapping.
     */
    private fun LocalMCPRuntimeCommandDispatchError.toRuntimeControlError(
        serverId: Long? = null,
        workerId: Long? = null
    ): LocalMCPRuntimeControlError = when (this) {
        is LocalMCPRuntimeCommandDispatchError.DispatchFailed -> when (error) {
            is WorkerCommandDispatchError.Rejected -> runtimeUnavailableError(
                serverId = serverId,
                workerId = workerId,
                reason = "Worker rejected command: ${error.rejection.reasonCode} - ${error.rejection.message}"
            )

            is WorkerCommandDispatchError.WorkerNotConnected -> runtimeUnavailableError(
                serverId = serverId,
                workerId = workerId,
                reason = "Assigned worker is not connected"
            )

            is WorkerCommandDispatchError.TimedOut -> runtimeUnavailableError(
                serverId = serverId,
                workerId = workerId,
                reason = "Worker command timed out after ${error.timeout}"
            )

            is WorkerCommandDispatchError.SessionDisconnected -> runtimeUnavailableError(
                serverId = serverId,
                workerId = workerId,
                reason = "Worker session disconnected${error.reason?.let { ": $it" } ?: ""}"
            )

            is WorkerCommandDispatchError.SendFailed -> runtimeUnavailableError(
                serverId = serverId,
                workerId = workerId,
                reason = "Failed to send worker command: ${error.reason}"
            )

            is WorkerCommandDispatchError.MalformedLifecyclePayload -> LocalMCPRuntimeControlError.InternalError(
                "Worker returned malformed lifecycle payload for ${error.commandType}/${error.messageType}: ${error.reason}"
            )

            is WorkerCommandDispatchError.DuplicateInteractionId -> LocalMCPRuntimeControlError.InternalError(
                "Worker dispatch failed due to duplicate interaction '${error.interactionId}'"
            )
        }

        is LocalMCPRuntimeCommandDispatchError.InvalidPayload -> LocalMCPRuntimeControlError.InternalError(
            "Worker payload mapping failed for $commandType: $details"
        )

        is LocalMCPRuntimeCommandDispatchError.CommandFailed -> runtimeUnavailableError(
            serverId = serverId,
            workerId = workerId,
            reason = "Worker command $commandType failed with code '$code': $message${details?.let { " ($it)" } ?: ""}"
        )

        is LocalMCPRuntimeCommandDispatchError.UnexpectedResultStatus -> runtimeUnavailableError(
            serverId = serverId,
            workerId = workerId,
            reason = "Worker completed $commandType with unsupported status '$status'"
        )
    }

    /**
     * Maps Local MCP server service errors into runtime-control API errors.
     *
     * @receiver Local MCP server service error.
     * @return Runtime-control error for route-level mapping.
     */
    private fun LocalMCPServerServiceError.toRuntimeControlError(): LocalMCPRuntimeControlError = when (this) {
        is LocalMCPServerNotFoundError -> LocalMCPRuntimeControlError.ServerNotFoundError(serverId)
        is LocalMCPServerUnauthorizedError -> LocalMCPRuntimeControlError.UnauthorizedError(userId, serverId)
        else -> LocalMCPRuntimeControlError.InternalError("Failed to validate server ownership for runtime control")
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
        is RefreshMCPToolsError.DuplicateName -> LocalMCPRuntimeControlError.InternalError("Tool refresh produced duplicate tool name '$name'")
        is RefreshMCPToolsError.ToolValidationError -> LocalMCPRuntimeControlError.InternalError("Tool refresh validation failed: $validationError")
        is RefreshMCPToolsError.OtherError -> LocalMCPRuntimeControlError.InternalError(message)
    }
}