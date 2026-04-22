package eu.torvian.chatbot.server.worker.mcp.runtimecontrol

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerCreateResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDeleteResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDiscoverToolsResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerGetRuntimeStatusResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerListRuntimeStatusesResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStartResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStopResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestConnectionResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerUpdateResultData

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
     * Dispatches `mcp.server.discover_tools` to a worker and returns decoded discovery result data.
     *
     * @param workerId Worker identifier targeted for command dispatch.
     * @param serverId Local MCP server identifier whose tools should be discovered.
     * @return Either orchestration error or typed discover-tools result data.
     */
    suspend fun discoverTools(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerDiscoverToolsResultData>

    /**
     * Dispatches `mcp.server.get_runtime_status` to a worker and returns decoded runtime status data.
     *
     * @param workerId Worker identifier targeted for command dispatch.
     * @param serverId Local MCP server identifier whose runtime status should be read.
     * @return Either orchestration error or typed runtime-status result data.
     */
    suspend fun getRuntimeStatus(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerGetRuntimeStatusResultData>

    /**
     * Dispatches `mcp.server.list_runtime_statuses` to a worker and returns decoded runtime-status list data.
     *
     * @param workerId Worker identifier targeted for command dispatch.
     * @return Either orchestration error or typed runtime-status list result data.
     */
    suspend fun listRuntimeStatuses(
        workerId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerListRuntimeStatusesResultData>

    /**
     * Dispatches `mcp.server.create` to a worker and returns decoded cache-sync result data.
     *
     * @param workerId Worker identifier targeted for command dispatch.
     * @param server Local MCP server configuration to upsert in worker cache.
     * @return Either orchestration error or typed create result data.
     */
    suspend fun createServer(
        workerId: Long,
        server: LocalMCPServerDto
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerCreateResultData>

    /**
     * Dispatches `mcp.server.update` to a worker and returns decoded cache-sync result data.
     *
     * @param workerId Worker identifier targeted for command dispatch.
     * @param server Local MCP server configuration to upsert in worker cache.
     * @return Either orchestration error or typed update result data.
     */
    suspend fun updateServer(
        workerId: Long,
        server: LocalMCPServerDto
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerUpdateResultData>

    /**
     * Dispatches `mcp.server.delete` to a worker and returns decoded cache-sync result data.
     *
     * @param workerId Worker identifier targeted for command dispatch.
     * @param serverId Local MCP server identifier to remove from worker cache.
     * @return Either orchestration error or typed delete result data.
     */
    suspend fun deleteServer(
        workerId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeCommandDispatchError, WorkerMcpServerDeleteResultData>
}


