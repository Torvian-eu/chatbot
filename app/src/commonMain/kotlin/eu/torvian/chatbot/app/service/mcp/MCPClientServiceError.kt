package eu.torvian.chatbot.app.service.mcp

/**
 * Common interface for all MCP client service errors.
 *
 * Provides standard properties for logging and debugging.
 */
sealed interface MCPClientError {
    /**
     * Comprehensive error message suitable for logging and debugging.
     */
    val message: String

    /**
     * Optional underlying cause (technical exception).
     */
    val cause: Throwable?
}

/**
 * Errors that can occur when starting and connecting to an MCP server.
 *
 * Used by: startAndConnect()
 */
sealed class StartAndConnectError : MCPClientError {
    /**
     * The process failed to start.
     */
    data class ProcessStartFailed(
        val serverId: Long,
        val reason: String,
        override val cause: Throwable? = null
    ) : StartAndConnectError() {
        override val message: String =
            "Failed to start MCP server process (ID: $serverId): $reason"
    }

    /**
     * The MCP SDK client is already connected for this server.
     */
    data class AlreadyConnected(
        val serverId: Long
    ) : StartAndConnectError() {
        override val message: String =
            "MCP server (ID: $serverId) is already connected"
        override val cause: Throwable? = null
    }

    /**
     * Failed to get process STDIO streams.
     */
    data class StreamsUnavailable(
        val serverId: Long,
        val reason: String
    ) : StartAndConnectError() {
        override val message: String =
            "Failed to get STDIO streams for MCP server (ID: $serverId): $reason"
        override val cause: Throwable? = null
    }

    /**
     * Failed to create or connect the MCP SDK client.
     */
    data class SDKConnectionFailed(
        val serverId: Long,
        val reason: String,
        override val cause: Throwable? = null
    ) : StartAndConnectError() {
        override val message: String =
            "Failed to connect MCP SDK client to server (ID: $serverId): $reason"
    }
}

/**
 * Errors that can occur when discovering tools from an MCP server.
 *
 * Used by: discoverTools()
 */
sealed class DiscoverToolsError : MCPClientError {
    /**
     * The MCP SDK client is not connected.
     */
    data class NotConnected(
        val serverId: Long
    ) : DiscoverToolsError() {
        override val message: String =
            "MCP server (ID: $serverId) is not connected"
        override val cause: Throwable? = null
    }

    /**
     * The MCP SDK failed to list tools.
     */
    data class SDKListToolsFailed(
        val serverId: Long,
        val reason: String,
        override val cause: Throwable? = null
    ) : DiscoverToolsError() {
        override val message: String =
            "MCP SDK failed to list tools from server (ID: $serverId): $reason"
    }
}

/**
 * Errors that can occur when calling a tool on an MCP server.
 *
 * Used by: callTool()
 */
sealed class CallToolError : MCPClientError {
    /**
     * The MCP SDK client is not connected.
     */
    data class NotConnected(
        val serverId: Long,
        val toolName: String
    ) : CallToolError() {
        override val message: String =
            "Cannot call tool '$toolName': MCP server (ID: $serverId) is not connected"
        override val cause: Throwable? = null
    }

    /**
     * The MCP SDK failed to call the tool.
     */
    data class SDKCallToolFailed(
        val serverId: Long,
        val toolName: String,
        val reason: String,
        override val cause: Throwable? = null
    ) : CallToolError() {
        override val message: String =
            "MCP SDK failed to call tool '$toolName' on server (ID: $serverId): $reason"
    }
}

/**
 * Errors that can occur when stopping an MCP server.
 *
 * Used by: stopServer()
 */
sealed class MCPStopServerError : MCPClientError {
    /**
     * Failed to disconnect the MCP SDK client.
     */
    data class DisconnectFailed(
        val serverId: Long,
        val reason: String,
        override val cause: Throwable? = null
    ) : MCPStopServerError() {
        override val message: String =
            "Failed to disconnect MCP SDK client for server (ID: $serverId): $reason"
    }

    /**
     * The process manager failed to stop the server process.
     */
    data class ProcessStopFailed(
        val serverId: Long,
        val reason: String,
        override val cause: Throwable? = null
    ) : MCPStopServerError() {
        override val message: String =
            "Failed to stop MCP server process (ID: $serverId): $reason"
    }
}

