package eu.torvian.chatbot.common.models.api.worker.protocol.constants

/**
 * Domain command types carried inside `command.request` payloads.
 */
object WorkerProtocolCommandTypes {
    /**
     * Command type used for direct/local tool execution that does not go through MCP.
     */
    const val TOOL_CALL = "tool.call"

    /**
     * Command type used for MCP-backed tool execution.
     */
    const val MCP_TOOL_CALL = "mcp.tool.call"
}