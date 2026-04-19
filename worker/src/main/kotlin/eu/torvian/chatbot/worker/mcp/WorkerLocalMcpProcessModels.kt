package eu.torvian.chatbot.worker.mcp

import kotlin.time.Instant

/**
 * Snapshot of one local MCP server process state from the worker runtime perspective.
 *
 * @property serverId Persisted local MCP server identifier.
 * @property state Current process lifecycle state.
 * @property pid Operating-system process identifier when available.
 * @property startedAt Instant when process start was recorded.
 * @property exitCode Process exit code for stopped/error states when known.
 * @property stoppedAt Instant when process stop was observed.
 * @property errorMessage Optional runtime diagnostic associated with a failed process state.
 */
data class WorkerLocalMcpProcessStatus(
    val serverId: Long,
    val state: WorkerLocalMcpProcessState,
    val pid: Long? = null,
    val startedAt: Instant? = null,
    val exitCode: Int? = null,
    val stoppedAt: Instant? = null,
    val errorMessage: String? = null
) {
    /**
     * Indicates whether the process is currently alive.
     */
    val isRunning: Boolean
        get() = state == WorkerLocalMcpProcessState.RUNNING
}

/**
 * Lifecycle state enumeration for worker-managed local MCP server processes.
 */
enum class WorkerLocalMcpProcessState {
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

/**
 * Marker contract for process-manager errors in the worker MCP runtime stack.
 */
sealed interface WorkerLocalMcpProcessManagerError {
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
sealed class WorkerLocalMcpStartProcessError : WorkerLocalMcpProcessManagerError {
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
        /**
         * Human-readable diagnostic message.
         */
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
        /**
         * Human-readable diagnostic message.
         */
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
        /**
         * Human-readable diagnostic message.
         */
        override val message: String =
            "Failed to start local MCP process (serverId=$serverId, command='$command'): $reason"
    }
}

/**
 * Errors returned by worker MCP process-manager stop operations.
 */
sealed class WorkerLocalMcpStopProcessError : WorkerLocalMcpProcessManagerError {
    /**
     * Stop request targeted a process that is already absent.
     *
     * @property serverId Persisted local MCP server identifier.
     */
    data class ProcessNotRunning(
        val serverId: Long
    ) : WorkerLocalMcpStopProcessError() {
        /**
         * Human-readable diagnostic message.
         */
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
        /**
         * Human-readable diagnostic message.
         */
        override val message: String =
            "Failed to stop local MCP process (serverId=$serverId): $reason"
    }
}

