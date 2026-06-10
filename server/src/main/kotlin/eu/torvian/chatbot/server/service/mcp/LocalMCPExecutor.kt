package eu.torvian.chatbot.server.service.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.WorkerMcpToolCallProtocolMappingError
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError
import eu.torvian.chatbot.server.worker.mcp.toolcall.LocalMCPToolCallDispatchError
import eu.torvian.chatbot.server.worker.mcp.toolcall.LocalMCPToolCallDispatchService

/**
 * Executes Local MCP tools through the worker that owns the persisted server configuration.
 *
 * This service is responsible for:
 * - Resolving the assigned worker for the Local MCP server
 * - Encoding the shared Local MCP tool-call request
 * - Dispatching the worker command through the server-owned worker transport
 * - Translating worker/runtime failures into structured tool-execution errors
 *
 */
class LocalMCPExecutor(
    private val localMCPServerDao: LocalMCPServerDao,
    private val toolCallDispatchService: LocalMCPToolCallDispatchService
) {
    /**
     * Executes a Local MCP tool on the worker assigned to the tool's server.
     *
     * The request relayed to the worker intentionally contains both the actual execution command and the
     * detached app authorization snapshot. The worker verifies that they still match before running the tool.
     *
     * @param sessionId Session that owns the tool call.
     * @param toolDefinition The tool definition, including the owning Local MCP server ID.
     * @param toolCall Persisted tool-call record to execute.
     * @param authorization App-approved execution authorization that should match the tool call.
     * @param signedAuthorization Detached signature metadata for [authorization].
     * @return A single execution outcome event containing either the worker result or a structured error.
     */
    suspend fun executeTool(
        sessionId: Long,
        toolDefinition: LocalMCPToolDefinition,
        toolCall: ToolCall,
        authorization: LocalMCPToolExecutionAuthorization,
        signedAuthorization: SignedRequest
    ): LocalMCPExecutorEvent {
        val request = LocalMCPToolCallRequest(
            toolCallId = toolCall.id,
            sessionId = sessionId,
            messageId = toolCall.messageId,
            toolDefinitionId = toolDefinition.id,
            toolName = toolCall.toolName,
            serverId = toolDefinition.serverId,
            mcpToolName = toolDefinition.mcpToolName,
            inputJson = toolCall.input,
            approved = authorization.approved,
            denialReason = authorization.denialReason,
            signedAuthorization = signedAuthorization
        )

        val workerId = localMCPServerDao.getServerById(toolDefinition.serverId).fold(
            ifLeft = {
                return LocalMCPExecutorEvent.ToolExecutionError(
                    toolCallId = toolCall.id,
                    error = LocalMCPExecutorError.OtherError(
                        "Local MCP server ${toolDefinition.serverId} was not found"
                    )
                )
            },
            ifRight = { it.workerId }
        )

        return toolCallDispatchService.dispatchToolCall(workerId, request)
            .fold(
                ifLeft = { error ->
                    LocalMCPExecutorEvent.ToolExecutionError(
                        toolCallId = toolCall.id,
                        error = error.toExecutorError()
                    )
                },
                ifRight = { result ->
                    LocalMCPExecutorEvent.ToolExecutionResult(
                        result = result
                    )
                }
            )
    }


    /**
     * Converts worker dispatch failures into executor-level tool execution errors.
     *
     * @receiver Worker dispatch failure returned by the typed tool-call adapter.
     * @return Structured executor error that can be persisted by the chat service.
     */
    private fun LocalMCPToolCallDispatchError.toExecutorError(): LocalMCPExecutorError = when (this) {
        is LocalMCPToolCallDispatchError.RequestMappingFailed -> {
            LocalMCPExecutorError.OtherError(
                "Failed to encode Local MCP tool call request for worker dispatch: ${error.describe()}"
            )
        }

        is LocalMCPToolCallDispatchError.DispatchFailed -> {
            when (val dispatchError = error) {
                is WorkerCommandDispatchError.WorkerNotConnected -> {
                    LocalMCPExecutorError.OtherError(
                        "Assigned worker ${dispatchError.workerId} is not connected"
                    )
                }

                is WorkerCommandDispatchError.SessionDisconnected -> {
                    LocalMCPExecutorError.OtherError(
                        "Assigned worker ${dispatchError.workerId} disconnected while executing tool call: ${dispatchError.reason ?: "unknown reason"}"
                    )
                }

                is WorkerCommandDispatchError.TimedOut -> {
                    LocalMCPExecutorError.Timeout(
                        "Tool execution timed out after ${dispatchError.timeout.inWholeSeconds} seconds"
                    )
                }

                is WorkerCommandDispatchError.SendFailed -> {
                    LocalMCPExecutorError.OtherError(
                        "Failed to send Local MCP tool call to worker ${dispatchError.workerId}: ${dispatchError.reason}"
                    )
                }

                is WorkerCommandDispatchError.MalformedLifecyclePayload -> {
                    LocalMCPExecutorError.OtherError(
                        "Worker returned malformed tool-call lifecycle payload (${dispatchError.messageType}): ${dispatchError.reason}"
                    )
                }

                is WorkerCommandDispatchError.Rejected -> {
                    LocalMCPExecutorError.OtherError(
                        "Worker rejected Local MCP tool call: ${dispatchError.rejection.message}"
                    )
                }

                is WorkerCommandDispatchError.DuplicateInteractionId -> {
                    LocalMCPExecutorError.OtherError(
                        "Worker command dispatch generated a duplicate interaction id: ${dispatchError.interactionId}"
                    )
                }
            }
        }

        is LocalMCPToolCallDispatchError.ResultMappingFailed -> {
            LocalMCPExecutorError.OtherError(
                "Failed to decode Local MCP tool call result from worker: ${error.describe()}"
            )
        }
    }

    /**
     * Formats shared worker-protocol mapping errors for executor diagnostics.
     *
     * @receiver Shared worker-protocol mapping failure.
     * @return Human-readable diagnostic string.
     */
    private fun WorkerMcpToolCallProtocolMappingError.describe(): String =
        when (this) {
            is WorkerMcpToolCallProtocolMappingError.InvalidCommandType -> {
                "expected $expected but received $actual"
            }

            is WorkerMcpToolCallProtocolMappingError.SerializationFailed -> {
                "$operation $targetType failed: ${details ?: "unknown serialization failure"}"
            }
        }
}
