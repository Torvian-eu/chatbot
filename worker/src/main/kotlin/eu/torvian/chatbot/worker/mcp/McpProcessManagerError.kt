package eu.torvian.chatbot.worker.mcp

/**
 * Marker contract for process-manager errors in the worker MCP runtime stack.
 */
sealed interface McpProcessManagerError {
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
 * Errors returned by worker MCP process-manager start operations.
 */
sealed class WorkerLocalMcpStartProcessError : McpProcessManagerError {
    /**
     * Start failed because configuration is invalid.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property reason Human-readable validation reason.
     */
    data class InvalidConfiguration(
        val serverId: Long,
        val reason: String
    ) : WorkerLocalMcpStartProcessError() {
        override val message: String =
            "Invalid local MCP server configuration (serverId=$serverId): $reason"

        /**
         * Invalid configuration has no technical throwable cause.
         */
        override val cause: Throwable? = null
    }

    /**
     * Start failed because a process is already running.
     *
     * @property serverId Persisted local MCP server identifier.
     */
    data class ProcessAlreadyRunning(
        val serverId: Long
    ) : WorkerLocalMcpStartProcessError() {
        override val message: String = "Local MCP process is already running (serverId=$serverId)"

        /**
         * Already-running condition has no technical throwable cause.
         */
        override val cause: Throwable? = null
    }

    /**
     * Start failed while invoking the operating-system process API.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property command Resolved command line used for process creation.
     * @property reason Human-readable failure reason.
     * @property cause Optional technical cause.
     */
    data class ProcessStartFailed(
        val serverId: Long,
        val command: String,
        val reason: String,
        override val cause: Throwable? = null
    ) : WorkerLocalMcpStartProcessError() {
        override val message: String =
            "Failed to start local MCP process (serverId=$serverId, command='$command'): $reason"
    }
}

/**
 * Errors returned by worker MCP process-manager stop operations.
 */
sealed class WorkerLocalMcpStopProcessError : McpProcessManagerError {
    /**
     * Stop request targeted a process that is already absent.
     *
     * @property serverId Persisted local MCP server identifier.
     */
    data class ProcessNotRunning(
        val serverId: Long
    ) : WorkerLocalMcpStopProcessError() {
        override val message: String = "Local MCP process is not running (serverId=$serverId)"

        /**
         * Not-running condition has no technical throwable cause.
         */
        override val cause: Throwable? = null
    }

    /**
     * Stop failed because process termination did not complete correctly.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property reason Human-readable failure reason.
     * @property cause Optional technical cause.
     */
    data class ProcessStopFailed(
        val serverId: Long,
        val reason: String,
        override val cause: Throwable? = null
    ) : WorkerLocalMcpStopProcessError() {
        override val message: String =
            "Failed to stop local MCP process (serverId=$serverId): $reason"
    }
}