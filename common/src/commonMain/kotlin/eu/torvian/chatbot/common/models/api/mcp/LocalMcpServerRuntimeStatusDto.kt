package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Shared runtime status snapshot for one persisted Local MCP server.
 *
 * This DTO is the cross-module read model used by worker, server, and app to represent
 * runtime/process state without exposing app-local runtime types.
 *
 * @property serverId Persisted Local MCP server identifier.
 * @property state Current runtime state as observed by the worker.
 * @property pid Operating-system process identifier when known.
 * @property startedAt Timestamp when the process was started.
 * @property exitCode Process exit code for stopped/error states when known.
 * @property stoppedAt Timestamp when the process was observed as stopped.
 * @property errorMessage Optional runtime diagnostic message.
 * @property connectedAt Timestamp when the worker MCP client connection was established.
 * @property lastActivityAt Timestamp of the latest observed runtime activity.
 */
@Serializable
data class LocalMcpServerRuntimeStatusDto(
    val serverId: Long,
    val state: LocalMcpServerRuntimeStateDto,
    val pid: Long? = null,
    val startedAt: Instant? = null,
    val exitCode: Int? = null,
    val stoppedAt: Instant? = null,
    val errorMessage: String? = null,
    val connectedAt: Instant? = null,
    val lastActivityAt: Instant? = null
)

/**
 * Lifecycle state enum for worker-observed Local MCP runtime status.
 */
@Serializable
enum class LocalMcpServerRuntimeStateDto {
    /** Runtime process is running. */
    RUNNING,

    /** Runtime process is stopped. */
    STOPPED,

    /** Runtime process is in an error state. */
    ERROR
}

