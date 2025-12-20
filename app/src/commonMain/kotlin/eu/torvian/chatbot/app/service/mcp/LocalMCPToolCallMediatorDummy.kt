package eu.torvian.chatbot.app.service.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Dummy implementation of [LocalMCPToolCallMediator] for WASM/JS and other platforms
 * that do not (yet) support local MCP tool calls.
 */
class LocalMCPToolCallMediatorDummy : LocalMCPToolCallMediator {
    override fun mediate(requestFlow: Flow<LocalMCPToolCallRequest>): Flow<LocalMCPToolCallResult> {
        // always return an empty flow
        return flowOf()
    }
}
