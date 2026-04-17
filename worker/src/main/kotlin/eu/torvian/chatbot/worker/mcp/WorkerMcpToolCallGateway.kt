package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import kotlinx.serialization.json.JsonObject

/**
 * Gateway abstraction that performs the actual MCP tool invocation.
 *
 * This keeps protocol/DTO mapping independent from process or SDK lifecycle concerns.
 */
interface WorkerMcpToolCallGateway {

    /**
     * Calls a tool on a configured local MCP server.
     *
     * @param serverId Local MCP server identifier.
     * @param toolName MCP tool name to invoke.
     * @param arguments Parsed JSON arguments passed to the tool.
     * @return Either gateway-level logical error or tool invocation outcome.
     */
    suspend fun callTool(
        serverId: Long,
        toolName: String,
        arguments: JsonObject
    ): Either<WorkerMcpToolCallGatewayError, WorkerMcpToolCallOutcome?>
}