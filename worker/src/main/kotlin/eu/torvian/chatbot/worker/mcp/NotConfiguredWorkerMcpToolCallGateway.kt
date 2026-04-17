package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import kotlinx.serialization.json.JsonObject

/**
 * Fallback gateway used until a concrete MCP backend is wired into the worker module.
 */
class NotConfiguredWorkerMcpToolCallGateway : WorkerMcpToolCallGateway {

    /**
     * Always returns a logical `NotConfigured` error.
     *
     * @param serverId Local MCP server identifier from the command request.
     * @param toolName MCP tool name from the command request.
     * @param arguments Parsed JSON arguments from the command request.
     * @return Always returns [WorkerMcpToolCallGatewayError.NotConfigured].
     */
    override suspend fun callTool(
        serverId: Long,
        toolName: String,
        arguments: JsonObject
    ): Either<WorkerMcpToolCallGatewayError, WorkerMcpToolCallOutcome?> {
        return Either.Left(
            WorkerMcpToolCallGatewayError.NotConfigured(
                message = "Worker MCP gateway is not configured yet for serverId=$serverId toolName=$toolName"
            )
        )
    }
}