package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult

/**
 * Executes worker-side MCP tool calls requested through the worker protocol.
 */
interface McpToolCallExecutor {

    /**
     * Executes one MCP tool call request and maps it to the shared result DTO.
     *
     * @param request Tool-call request received from the server.
     * @return Final mapped tool-call result for protocol response emission.
     */
    suspend fun execute(request: LocalMCPToolCallRequest): LocalMCPToolCallResult
}

