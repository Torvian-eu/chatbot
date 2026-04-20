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

    /**
     * Command type used to start an MCP server runtime instance.
     */
    const val MCP_SERVER_START = "mcp.server.start"

    /**
     * Command type used to stop an MCP server runtime instance.
     */
    const val MCP_SERVER_STOP = "mcp.server.stop"

    /**
     * Command type used to test MCP server runtime connectivity.
     */
    const val MCP_SERVER_TEST_CONNECTION = "mcp.server.test_connection"

    /**
     * Command type used to discover runtime MCP tools for a server.
     */
    const val MCP_SERVER_DISCOVER_TOOLS = "mcp.server.discover_tools"
}