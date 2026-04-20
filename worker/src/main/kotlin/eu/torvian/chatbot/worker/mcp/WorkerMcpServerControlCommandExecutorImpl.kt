package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerControlErrorResultData
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

/**
 * Runtime-backed [WorkerMcpServerControlCommandExecutor] implementation.
 *
 * This adapter intentionally remains thin and delegates runtime behavior to
 * [WorkerLocalMcpRuntimeService], while mapping runtime errors to protocol result DTOs.
 *
 * @property runtimeService Worker runtime service that performs MCP process/client operations.
 */
class WorkerMcpServerControlCommandExecutorImpl(
    private val runtimeService: WorkerLocalMcpRuntimeService
) : WorkerMcpServerControlCommandExecutor {

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

    /**
     * Maps runtime errors to protocol result error payload shape.
     *
     * @receiver Runtime error returned by worker-local MCP runtime service.
     * @param serverId Persisted local MCP server identifier bound to command execution.
     * @return Protocol error payload data.
     */
    private fun WorkerLocalMcpRuntimeError.toProtocolError(
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

