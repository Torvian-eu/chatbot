package eu.torvian.chatbot.server.worker.mcp.runtimecontrol

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerRefreshToolsResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStartResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStopResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestConnectionResultData

/**
 * Server-side typed adapter over worker command dispatch for Local MCP runtime-control operations.
 *
 * This abstraction hides generic worker lifecycle details from higher-level runtime-control services
 * and exposes typed outcomes for the MCP runtime command set.
 */
interface LocalMCPRuntimeCommandDispatchService {
    /**
     * Dispatches `mcp.server.start` to a worker and returns decoded start result data.
     *
     * @param workerId Worker identifier targeted for command dispatch.
     * @param serverId Local MCP server identifier to start.
     * @return Either orchestration error or typed start result data.
     */
    suspend fun startServer(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerStartResultData>

    /**
     * Dispatches `mcp.server.stop` to a worker and returns decoded stop result data.
     *
     * @param workerId Worker identifier targeted for command dispatch.
     * @param serverId Local MCP server identifier to stop.
     * @return Either orchestration error or typed stop result data.
     */
    suspend fun stopServer(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerStopResultData>

    /**
     * Dispatches `mcp.server.test_connection` to a worker and returns decoded test result data.
     *
     * @param workerId Worker identifier targeted for command dispatch.
     * @param serverId Local MCP server identifier to test.
     * @return Either orchestration error or typed test-connection result data.
     */
    suspend fun testConnection(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerTestConnectionResultData>

    /**
     * Dispatches `mcp.server.refresh_tools` to a worker and returns decoded refresh result data.
     *
     * @param workerId Worker identifier targeted for command dispatch.
     * @param serverId Local MCP server identifier whose tools should be refreshed.
     * @return Either orchestration error or typed refresh-tools result data.
     */
    suspend fun refreshTools(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerRefreshToolsResultData>
}


