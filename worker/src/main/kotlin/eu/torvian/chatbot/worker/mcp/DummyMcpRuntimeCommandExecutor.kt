package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStateDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.*

/**
 * Deterministic dummy implementation for worker local MCP runtime commands.
 *
 * This implementation intentionally does not start processes or talk to MCP runtimes yet. It returns
 * stable payload shapes so server and protocol lifecycle wiring can be validated end-to-end.
 */
class DummyMcpRuntimeCommandExecutor : McpRuntimeCommandExecutor {
    override suspend fun startServer(
        request: WorkerMcpServerStartCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerStartResultData> {
        return WorkerMcpServerStartResultData(serverId = request.serverId).right()
    }

    override suspend fun stopServer(
        request: WorkerMcpServerStopCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerStopResultData> {
        return WorkerMcpServerStopResultData(serverId = request.serverId).right()
    }

    override suspend fun testConnection(
        request: WorkerMcpServerTestConnectionCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerTestConnectionResultData> {
        return WorkerMcpServerTestConnectionResultData(
            serverId = request.serverId,
            success = true,
            discoveredToolCount = DUMMY_DISCOVERED_TOOL_COUNT,
            message = DUMMY_TEST_CONNECTION_MESSAGE
        ).right()
    }

    override suspend fun discoverTools(
        request: WorkerMcpServerDiscoverToolsCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerDiscoverToolsResultData> {
        return WorkerMcpServerDiscoverToolsResultData(
            serverId = request.serverId,
            tools = emptyList()
        ).right()
    }

    override suspend fun getRuntimeStatus(
        request: WorkerMcpServerGetRuntimeStatusCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerGetRuntimeStatusResultData> {
        return WorkerMcpServerGetRuntimeStatusResultData(
            status = LocalMcpServerRuntimeStatusDto(
                serverId = request.serverId,
                state = LocalMcpServerRuntimeStateDto.STOPPED,
                errorMessage = "Dummy runtime-status response"
            )
        ).right()
    }

    override suspend fun listRuntimeStatuses(
        request: WorkerMcpServerListRuntimeStatusesCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerListRuntimeStatusesResultData> {
        return WorkerMcpServerListRuntimeStatusesResultData(statuses = emptyList()).right()
    }

    override suspend fun createServer(
        request: WorkerMcpServerCreateCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerCreateResultData> {
        return WorkerMcpServerCreateResultData(serverId = request.server.id).right()
    }

    override suspend fun updateServer(
        request: WorkerMcpServerUpdateCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerUpdateResultData> {
        return WorkerMcpServerUpdateResultData(serverId = request.server.id).right()
    }

    override suspend fun deleteServer(
        request: WorkerMcpServerDeleteCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerDeleteResultData> {
        return WorkerMcpServerDeleteResultData(serverId = request.serverId).right()
    }

    companion object {
        /**
         * Stable dummy tool count returned by test-connection responses.
         */
        const val DUMMY_DISCOVERED_TOOL_COUNT: Int = 3

        /**
         * Stable explanatory message returned by dummy test-connection responses.
         */
        const val DUMMY_TEST_CONNECTION_MESSAGE: String =
            "Dummy worker MCP runtime control implementation; real runtime not yet enabled"
    }
}