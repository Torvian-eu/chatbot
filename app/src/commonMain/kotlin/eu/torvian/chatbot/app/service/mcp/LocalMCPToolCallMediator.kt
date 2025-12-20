package eu.torvian.chatbot.app.service.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import kotlinx.coroutines.flow.Flow

interface LocalMCPToolCallMediator {
    /**
     * Mediates tool call requests from the LLM to the MCP client.
     *
     * This method takes a flow of tool call requests and returns a flow of tool call results.
     * It is responsible for:
     * - Parsing input JSON
     * - Executing tool calls on the MCP client
     * - Handling errors
     * - Returning results to the LLM
     *
     * @param requestFlow Flow of tool call requests from the LLM
     * @return Flow of tool call results to be sent back to the LLM
     */
    fun mediate(requestFlow: Flow<LocalMCPToolCallRequest>): Flow<LocalMCPToolCallResult>
}
