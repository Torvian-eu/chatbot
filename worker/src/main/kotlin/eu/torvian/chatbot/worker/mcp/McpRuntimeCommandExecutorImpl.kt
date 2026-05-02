package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerControlErrorResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerCreateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerCreateResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDeleteCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDeleteResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpDiscoveredToolData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDiscoverToolsCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDiscoverToolsResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerGetRuntimeStatusCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerGetRuntimeStatusResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerListRuntimeStatusesCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerListRuntimeStatusesResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStartCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStartResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStopCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStopResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestConnectionCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestConnectionResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestDraftConnectionCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestDraftConnectionResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerUpdateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerUpdateResultData
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto

/**
 * Runtime-backed [McpRuntimeCommandExecutor] implementation.
 *
 * This adapter intentionally remains thin and delegates runtime behavior to
 * [McpRuntimeService], while mapping runtime errors to protocol result DTOs.
 *
 * @property runtimeService Worker runtime service that performs MCP process/client operations.
 * @property configStore Worker-local assigned MCP server cache updated by create/update/delete sync commands.
 */
class McpRuntimeCommandExecutorImpl(
    private val runtimeService: McpRuntimeService,
    private val configStore: McpServerConfigStore
) : McpRuntimeCommandExecutor {

    override suspend fun startServer(
        request: WorkerMcpServerStartCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerStartResultData> {
        return runtimeService.startServer(request.serverId).fold(
            ifLeft = { runtimeError -> runtimeError.toProtocolError(request.serverId).left() },
            ifRight = { WorkerMcpServerStartResultData(serverId = request.serverId).right() }
        )
    }

    override suspend fun stopServer(
        request: WorkerMcpServerStopCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerStopResultData> {
        return runtimeService.stopServer(request.serverId).fold(
            ifLeft = { runtimeError -> runtimeError.toProtocolError(request.serverId).left() },
            ifRight = { WorkerMcpServerStopResultData(serverId = request.serverId).right() }
        )
    }

    override suspend fun testConnection(
        request: WorkerMcpServerTestConnectionCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerTestConnectionResultData> {
        return runtimeService.testConnection(request.serverId).fold(
            ifLeft = { runtimeError -> runtimeError.toProtocolError(request.serverId).left() },
            ifRight = { outcome ->
                WorkerMcpServerTestConnectionResultData(
                    serverId = request.serverId,
                    success = true,
                    discoveredToolCount = outcome.discoveredToolCount,
                    message = outcome.message
                ).right()
            }
        )
    }

    override suspend fun discoverTools(
        request: WorkerMcpServerDiscoverToolsCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerDiscoverToolsResultData> {
        return runtimeService.discoverTools(request.serverId).fold(
            ifLeft = { runtimeError -> runtimeError.toProtocolError(request.serverId).left() },
            ifRight = { discoveredTools ->
                WorkerMcpServerDiscoverToolsResultData(
                    serverId = request.serverId,
                    tools = discoveredTools.map { tool ->
                        WorkerMcpDiscoveredToolData(
                            name = tool.name,
                            description = tool.description,
                            inputSchema = tool.inputSchema,
                            outputSchema = tool.outputSchema
                        )
                    }
                ).right()
            }
        )
    }

    override suspend fun getRuntimeStatus(
        request: WorkerMcpServerGetRuntimeStatusCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerGetRuntimeStatusResultData> {
        return runtimeService.getRuntimeStatus(request.serverId).fold(
            ifLeft = { runtimeError -> runtimeError.toProtocolError(request.serverId).left() },
            ifRight = { status -> WorkerMcpServerGetRuntimeStatusResultData(status = status).right() }
        )
    }

    override suspend fun listRuntimeStatuses(
        request: WorkerMcpServerListRuntimeStatusesCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerListRuntimeStatusesResultData> {
        return WorkerMcpServerListRuntimeStatusesResultData(
            statuses = runtimeService.listRuntimeStatuses()
        ).right()
    }

    override suspend fun createServer(
        request: WorkerMcpServerCreateCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerCreateResultData> {
        return runConfigSyncCommand(serverId = request.server.id) {
            configStore.upsertServer(request.server)
            WorkerMcpServerCreateResultData(serverId = request.server.id)
        }
    }

    override suspend fun updateServer(
        request: WorkerMcpServerUpdateCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerUpdateResultData> {
        return runConfigSyncCommand(serverId = request.server.id) {
            configStore.upsertServer(request.server)
            WorkerMcpServerUpdateResultData(serverId = request.server.id)
        }
    }

    override suspend fun deleteServer(
        request: WorkerMcpServerDeleteCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerDeleteResultData> {
        return runConfigSyncCommand(serverId = request.serverId) {
            configStore.removeServer(request.serverId)
            WorkerMcpServerDeleteResultData(serverId = request.serverId)
        }
    }

    override suspend fun testDraftConnection(
        request: WorkerMcpServerTestDraftConnectionCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerTestDraftConnectionResultData> {
        // Use a temporary runtime-only identifier so draft tests do not collide with tracked servers.
        val tempServerId = -System.currentTimeMillis()

        val config = LocalMCPServerDto(
            id = tempServerId,
            userId = 0L,
            workerId = 0L,
            name = request.name,
            command = request.command,
            arguments = request.arguments,
            workingDirectory = request.workingDirectory,
            environmentVariables = request.environmentVariables,
            secretEnvironmentVariables = request.secretEnvironmentVariables,
            createdAt = kotlin.time.Clock.System.now(),
            updatedAt = kotlin.time.Clock.System.now()
        )

        return runtimeService.testDraftConnection(config).fold(
            ifLeft = { runtimeError -> runtimeError.toProtocolError(tempServerId).left() },
            ifRight = { outcome ->
                WorkerMcpServerTestDraftConnectionResultData(
                    success = true,
                    discoveredToolCount = outcome.discoveredToolCount,
                    message = outcome.message
                ).right()
            }
        )
    }

    /**
     * Executes one config-sync cache mutation and maps technical failures to protocol error data.
     *
     * @param serverId Persisted Local MCP server identifier associated with the mutation.
     * @param block Mutation action that returns typed success result data.
     * @return Either protocol error payload data or typed success result data.
     */
    private inline fun <TSuccess> runConfigSyncCommand(
        serverId: Long,
        block: () -> TSuccess
    ): Either<WorkerMcpServerControlErrorResultData, TSuccess> {
        return try {
            block().right()
        } catch (exception: Exception) {
            WorkerMcpServerControlErrorResultData(
                serverId = serverId,
                code = "CONFIG_SYNC_FAILED",
                message = "Failed to apply MCP server config sync command",
                details = exception.message
            ).left()
        }
    }

    /**
     * Maps runtime errors to protocol result error payload shape.
     *
     * @receiver Runtime error returned by worker-local MCP runtime service.
     * @param serverId Persisted local MCP server identifier bound to command execution.
     * @return Protocol error payload data.
     */
    private fun McpRuntimeError.toProtocolError(
        serverId: Long
    ): WorkerMcpServerControlErrorResultData {
        return WorkerMcpServerControlErrorResultData(
            serverId = serverId,
            code = code,
            message = message,
            details = details
        )
    }
}
