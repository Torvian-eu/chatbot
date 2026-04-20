package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
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
 * Executes worker-side MCP server runtime-control commands.
 *
 * This abstraction keeps protocol lifecycle handling independent from command-specific runtime logic,
 * so deterministic dummy behavior can later be replaced with real runtime integration without touching
 * transport routing.
 */
interface WorkerMcpServerControlCommandExecutor {
    /**
     * Executes an MCP server start command.
     *
     * @param request Typed start-command input data.
     * @return Either typed failure data or typed success data for the command result.
     */
    suspend fun startServer(
        request: WorkerMcpServerStartCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerStartResultData>

    /**
     * Executes an MCP server stop command.
     *
     * @param request Typed stop-command input data.
     * @return Either typed failure data or typed success data for the command result.
     */
    suspend fun stopServer(
        request: WorkerMcpServerStopCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerStopResultData>

    /**
     * Executes an MCP server connectivity test command.
     *
     * @param request Typed test-connection command input data.
     * @return Either typed failure data or typed success data for the command result.
     */
    suspend fun testConnection(
        request: WorkerMcpServerTestConnectionCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerTestConnectionResultData>

    /**
     * Executes an MCP server discover-tools command.
     *
     * @param request Typed discover-tools command input data.
     * @return Either typed failure data or typed success data for the command result.
     */
    suspend fun discoverTools(
        request: WorkerMcpServerDiscoverToolsCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerDiscoverToolsResultData>

    /**
     * Executes an MCP server get-runtime-status command.
     *
     * @param request Typed get-runtime-status command input data.
     * @return Either typed failure data or typed success data for the command result.
     */
    suspend fun getRuntimeStatus(
        request: WorkerMcpServerGetRuntimeStatusCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerGetRuntimeStatusResultData>

    /**
     * Executes an MCP server list-runtime-statuses command.
     *
     * @param request Typed list-runtime-statuses command input data.
     * @return Either typed failure data or typed success data for the command result.
     */
    suspend fun listRuntimeStatuses(
        request: WorkerMcpServerListRuntimeStatusesCommandData
    ): Either<WorkerMcpServerControlErrorResultData, WorkerMcpServerListRuntimeStatusesResultData>
}

