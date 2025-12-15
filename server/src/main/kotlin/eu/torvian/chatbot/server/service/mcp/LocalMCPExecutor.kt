package eu.torvian.chatbot.server.service.mcp

import eu.torvian.chatbot.common.models.tool.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.tool.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Executes MCP server processes locally on the client. (remotely from server perspective)
 *
 * This service is responsible for:
 * - Executing tools on local MCP servers
 * - Validating tool input
 * - Handling tool execution errors
 * - Managing tool execution timeouts
 *
 */
class LocalMCPExecutor(
) {
    /**
     * Executes a tool on a local MCP server.
     *
     * @param toolDefinition The definition of the tool to execute, including configuration.
     * @param toolCallId Unique identifier for the tool call
     * @param inputJson JSON input for the tool (may be null or invalid JSON)
     * @param responseFlow Flow of tool execution results from the client
     * @return Flow of execution events
     */
    fun executeTool(
        toolDefinition: LocalMCPToolDefinition,
        toolCallId: Long,
        inputJson: String?,
        responseFlow: Flow<LocalMCPToolCallResult>
    ): Flow<LocalMCPExecutorEvent> = channelFlow {
        // Send tool execution request event to client
        send(
            LocalMCPExecutorEvent.ToolExecutionRequest(
                request = LocalMCPToolCallRequest(
                    toolCallId = toolCallId,
                    serverId = toolDefinition.serverId,
                    toolName = toolDefinition.mcpToolName,
                    inputJson = inputJson
                )
            )
        )

        // Wait for a matching tool execution result from the client, with a timeout.
        try {
            val result = withTimeout(60_000) {
                responseFlow.first { it.toolCallId == toolCallId }
            }
            send(
                LocalMCPExecutorEvent.ToolExecutionResult(
                    result = result
                )
            )
        } catch (_: TimeoutCancellationException) {
            send(
                LocalMCPExecutorEvent.ToolExecutionError(
                    toolCallId = toolCallId,
                    error = LocalMCPExecutorError.Timeout("Tool execution timed out")
                )
            )
        } catch (_: NoSuchElementException) {
            send(
                LocalMCPExecutorEvent.ToolExecutionError(
                    toolCallId = toolCallId,
                    error = LocalMCPExecutorError.OtherError("No matching result received from client")
                )
            )
        }
    }
}
