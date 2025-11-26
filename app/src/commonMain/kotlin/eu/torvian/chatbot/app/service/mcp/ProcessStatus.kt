package eu.torvian.chatbot.app.service.mcp

import kotlinx.datetime.Instant

/**
 * Represents the current status of an MCP server process.
 *
 * @property serverId The unique identifier of the MCP server
 * @property state The current state of the process
 * @property pid The process ID (if running)
 * @property startedAt When the process was started (if running)
 * @property exitCode The exit code (if stopped)
 * @property stoppedAt When the process stopped (if stopped)
 * @property errorMessage Optional error message if the process is in an error state
 */
data class ProcessStatus(
    val serverId: Long,
    val state: ProcessState,
    val pid: Long? = null,
    val startedAt: Instant? = null,
    val exitCode: Int? = null,
    val stoppedAt: Instant? = null,
    val errorMessage: String? = null
) {
    /**
     * Returns whether the process is currently running.
     */
    val isRunning: Boolean
        get() = state == ProcessState.RUNNING

    /**
     * Returns whether the process has stopped.
     */
    val isStopped: Boolean
        get() = state == ProcessState.STOPPED

    /**
     * Returns whether the process is in an error state.
     */
    val hasError: Boolean
        get() = state == ProcessState.ERROR
}

/**
 * Enumeration of possible process states.
 */
enum class ProcessState {
    /**
     * The process is currently running.
     */
    RUNNING,

    /**
     * The process has stopped (either gracefully or forcibly).
     */
    STOPPED,

    /**
     * The process is in an error state (crashed or failed to start).
     */
    ERROR
}

