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
data class McpProcessStatus(
    val serverId: Long,
    val state: McpProcessState,
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
        get() = state == McpProcessState.RUNNING
}