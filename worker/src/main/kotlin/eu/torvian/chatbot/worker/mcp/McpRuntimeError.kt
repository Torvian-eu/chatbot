package eu.torvian.chatbot.worker.mcp

/**
 * Logical runtime errors for worker MCP server control operations.
 */
sealed interface McpRuntimeError {
    /**
     * Stable machine-readable runtime error code.
     */
    val code: String

    /**
     * Human-readable runtime error message.
     */
    val message: String

    /**
     * Optional structured runtime diagnostics rendered as a simple string.
     */
    val details: String?

    /**
     * No configuration was available for the requested server identifier.
     *
     * @property serverId Persisted local MCP server identifier.
     */
    data class ServerConfigMissing(
        val serverId: Long
    ) : McpRuntimeError {
        override val code: String = "SERVER_CONFIG_MISSING"
        override val message: String = "No local MCP server config available for serverId=$serverId"
        override val details: String = "The worker config store has no entry for this server"
    }

    /**
     * Runtime start operation failed.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property message Human-readable runtime error message.
     * @property details Optional diagnostics.
     */
    data class StartFailed(
        val serverId: Long,
        override val message: String,
        override val details: String? = null
    ) : McpRuntimeError {
        override val code: String = "START_FAILED"
    }

    /**
     * Runtime stop operation failed.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property message Human-readable runtime error message.
     * @property details Optional diagnostics.
     */
    data class StopFailed(
        val serverId: Long,
        override val message: String,
        override val details: String? = null
    ) : McpRuntimeError {
        override val code: String = "STOP_FAILED"
    }

    /**
     * Runtime tool-discovery operation failed.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property message Human-readable runtime error message.
     * @property details Optional diagnostics.
     */
    data class DiscoveryFailed(
        val serverId: Long,
        override val message: String,
        override val details: String? = null
    ) : McpRuntimeError {
        override val code: String = "DISCOVERY_FAILED"
    }

    /**
     * Test-connection cleanup failed after a temporary start.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property message Human-readable runtime error message.
     * @property details Optional diagnostics.
     */
    data class CleanupFailed(
        val serverId: Long,
        override val message: String,
        override val details: String? = null
    ) : McpRuntimeError {
        override val code: String = "CLEANUP_FAILED"
    }

    /**
     * Tool-call execution failed.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property toolName Requested MCP tool name.
     * @property message Human-readable runtime error message.
     * @property details Optional diagnostics.
     */
    data class ToolCallFailed(
        val serverId: Long,
        val toolName: String,
        override val message: String,
        override val details: String? = null
    ) : McpRuntimeError {
        override val code: String = "TOOL_CALL_FAILED"
    }
}