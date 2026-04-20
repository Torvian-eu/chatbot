package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStateDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerControlErrorResultData
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
 * Deterministic dummy implementation for MCP server runtime-control commands.
 *
 * This implementation intentionally does not start processes or talk to MCP runtimes yet. It returns
 * stable payload shapes so server and protocol lifecycle wiring can be validated end-to-end.
 */
class DummyWorkerMcpServerControlCommandExecutor : WorkerMcpServerControlCommandExecutor {
    /**
     * @param request Typed start-command input data.
     * @return Deterministic successful start result carrying the same server identifier.
     */
    override suspend fun startServer(
        request: WorkerMcpServerStartCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerStartResultData> {
        return WorkerMcpServerStartResultData(serverId = request.serverId).right()
    }

    /**
     * @param request Typed stop-command input data.
     * @return Deterministic successful stop result carrying the same server identifier.
     */
    override suspend fun stopServer(
        request: WorkerMcpServerStopCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerStopResultData> {
        return WorkerMcpServerStopResultData(serverId = request.serverId).right()
    }

    /**
     * @param request Typed test-connection command input data.
     * @return Deterministic successful test-connection result with stable diagnostics.
     */
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

    /**
     * @param request Typed discover-tools command input data.
     * @return Deterministic successful discover result with an empty tool list.
     */
    override suspend fun discoverTools(
        request: WorkerMcpServerDiscoverToolsCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerDiscoverToolsResultData> {
        return WorkerMcpServerDiscoverToolsResultData(
            serverId = request.serverId,
            tools = emptyList()
        ).right()
    }

    /**
     * @param request Typed get-runtime-status command input data.
     * @return Deterministic runtime-status result with a stopped state.
     */
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

    /**
     * @param request Typed list-runtime-statuses command input data.
     * @return Deterministic empty runtime-status list.
     */
    override suspend fun listRuntimeStatuses(
        request: WorkerMcpServerListRuntimeStatusesCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerListRuntimeStatusesResultData> {
        return WorkerMcpServerListRuntimeStatusesResultData(statuses = emptyList()).right()
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