package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerControlErrorResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerRefreshToolsCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerRefreshToolsResultData
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
     * @param request Typed refresh-tools command input data.
     * @return Deterministic successful refresh result with an empty tool diff.
     */
    override suspend fun refreshTools(
        request: WorkerMcpServerRefreshToolsCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerRefreshToolsResultData> {
        return WorkerMcpServerRefreshToolsResultData(
            serverId = request.serverId,
            addedTools = emptyList(),
            updatedTools = emptyList(),
            deletedTools = emptyList()
        ).right()
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