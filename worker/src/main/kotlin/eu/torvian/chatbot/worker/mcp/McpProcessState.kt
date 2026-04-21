package eu.torvian.chatbot.worker.mcp

/**
 * Lifecycle state enumeration for worker-managed local MCP server processes.
 */
enum class McpProcessState {
    /**
     * Process is currently running.
     */
    RUNNING,

    /**
     * Process is not running.
     */
    STOPPED,

    /**
     * Process terminated unexpectedly or entered a failed state.
     */
    ERROR
}