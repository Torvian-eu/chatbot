package eu.torvian.chatbot.worker.mcp

/**
 * Marker contract for worker-local MCP client runtime errors.
 */
sealed interface WorkerMcpClientError {
    /**
     * Human-readable diagnostic message.
     */
    val message: String

    /**
     * Optional technical cause.
     */
    val cause: Throwable?
}

/**
 * Errors from client start/connect operations.
 */
sealed class WorkerMcpClientStartError : WorkerMcpClientError {
    /**
     * Start/connect failed because a client already exists.
     *
     * @property serverId Persisted local MCP server identifier.
     */
    data class AlreadyConnected(
        val serverId: Long
    ) : WorkerMcpClientStartError() {
        /**
         * Human-readable diagnostic message.
         */
        override val message: String = "MCP client already connected (serverId=$serverId)"

        /**
         * Already-connected condition has no technical throwable cause.
         */
        override val cause: Throwable? = null
    }

    /**
     * Start/connect failed because process start failed.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property reason Human-readable reason.
     * @property cause Optional technical cause.
     */
    data class ProcessStartFailed(
        val serverId: Long,
        val reason: String,
        override val cause: Throwable? = null
    ) : WorkerMcpClientStartError() {
        /**
         * Human-readable diagnostic message.
         */
        override val message: String = "Failed to start MCP process (serverId=$serverId): $reason"
    }

    /**
     * Start/connect failed because required stdio streams were unavailable.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property reason Human-readable reason.
     */
    data class StreamsUnavailable(
        val serverId: Long,
        val reason: String
    ) : WorkerMcpClientStartError() {
        /**
         * Human-readable diagnostic message.
         */
        override val message: String = "MCP stdio streams unavailable (serverId=$serverId): $reason"

        /**
         * Stream unavailability has no technical throwable cause.
         */
        override val cause: Throwable? = null
    }

    /**
     * Start/connect failed while establishing MCP SDK transport connection.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property reason Human-readable reason.
     * @property cause Optional technical cause.
     */
    data class ConnectionFailed(
        val serverId: Long,
        val reason: String,
        override val cause: Throwable? = null
    ) : WorkerMcpClientStartError() {
        /**
         * Human-readable diagnostic message.
         */
        override val message: String = "Failed to connect MCP client (serverId=$serverId): $reason"
    }
}

/**
 * Errors from client stop operations.
 */
sealed class WorkerMcpClientStopError : WorkerMcpClientError {
    /**
     * Stop request targeted a server without a registered client/process connection.
     *
     * @property serverId Persisted local MCP server identifier.
     */
    data class NotConnected(
        val serverId: Long
    ) : WorkerMcpClientStopError() {
        /**
         * Human-readable diagnostic message.
         */
        override val message: String = "MCP client is not connected (serverId=$serverId)"

        /**
         * Not-connected condition has no technical throwable cause.
         */
        override val cause: Throwable? = null
    }

    /**
     * Stop failed while closing MCP SDK client transport.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property reason Human-readable reason.
     * @property cause Optional technical cause.
     */
    data class DisconnectFailed(
        val serverId: Long,
        val reason: String,
        override val cause: Throwable? = null
    ) : WorkerMcpClientStopError() {
        /**
         * Human-readable diagnostic message.
         */
        override val message: String = "Failed to disconnect MCP client (serverId=$serverId): $reason"
    }

    /**
     * Stop failed while terminating the underlying process.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property reason Human-readable reason.
     * @property cause Optional technical cause.
     */
    data class ProcessStopFailed(
        val serverId: Long,
        val reason: String,
        override val cause: Throwable? = null
    ) : WorkerMcpClientStopError() {
        /**
         * Human-readable diagnostic message.
         */
        override val message: String = "Failed to stop MCP process (serverId=$serverId): $reason"
    }
}

/**
 * Errors from tool-discovery operations.
 */
sealed class WorkerMcpClientDiscoverToolsError : WorkerMcpClientError {
    /**
     * Discovery failed because no client is connected.
     *
     * @property serverId Persisted local MCP server identifier.
     */
    data class NotConnected(
        val serverId: Long
    ) : WorkerMcpClientDiscoverToolsError() {
        /**
         * Human-readable diagnostic message.
         */
        override val message: String = "MCP client is not connected for tool discovery (serverId=$serverId)"

        /**
         * Not-connected condition has no technical throwable cause.
         */
        override val cause: Throwable? = null
    }

    /**
     * Discovery failed due to MCP SDK list-tools failure.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property reason Human-readable reason.
     * @property cause Optional technical cause.
     */
    data class ListToolsFailed(
        val serverId: Long,
        val reason: String,
        override val cause: Throwable? = null
    ) : WorkerMcpClientDiscoverToolsError() {
        /**
         * Human-readable diagnostic message.
         */
        override val message: String = "MCP tool discovery failed (serverId=$serverId): $reason"
    }
}

