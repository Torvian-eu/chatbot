package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import kotlinx.serialization.Serializable

/**
 * Request data carried by `mcp.server.start` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier targeted for start.
 */
@Serializable
data class WorkerMcpServerStartCommandData(
    val serverId: Long
)

/**
 * Request data carried by `mcp.server.stop` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier targeted for stop.
 */
@Serializable
data class WorkerMcpServerStopCommandData(
    val serverId: Long
)

/**
 * Request data carried by `mcp.server.test_connection` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier targeted for connectivity testing.
 */
@Serializable
data class WorkerMcpServerTestConnectionCommandData(
    val serverId: Long
)

/**
 * Request data carried by `mcp.server.refresh_tools` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier targeted for tool refresh.
 */
@Serializable
data class WorkerMcpServerRefreshToolsCommandData(
    val serverId: Long
)

/**
 * Result data emitted for successful `mcp.server.start` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier that was started.
 */
@Serializable
data class WorkerMcpServerStartResultData(
    val serverId: Long
)

/**
 * Result data emitted for successful `mcp.server.stop` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier that was stopped.
 */
@Serializable
data class WorkerMcpServerStopResultData(
    val serverId: Long
)

/**
 * Result data emitted for `mcp.server.test_connection` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier that was tested.
 * @property success Whether the worker-side runtime connectivity check succeeded.
 * @property discoveredToolCount Number of tools discovered during the test call.
 * @property message Optional human-readable details about the test outcome.
 */
@Serializable
data class WorkerMcpServerTestConnectionResultData(
    val serverId: Long,
    val success: Boolean,
    val discoveredToolCount: Int,
    val message: String? = null
)

/**
 * Result data emitted for `mcp.server.refresh_tools` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier that was refreshed.
 * @property addedTools Tool definitions discovered as newly added.
 * @property updatedTools Tool definitions that changed and were updated.
 * @property deletedTools Tool definitions removed by differential refresh.
 */
@Serializable
data class WorkerMcpServerRefreshToolsResultData(
    val serverId: Long,
    val addedTools: List<LocalMCPToolDefinition>,
    val updatedTools: List<LocalMCPToolDefinition>,
    val deletedTools: List<LocalMCPToolDefinition>
)

/**
 * Result data emitted for failed MCP server-control worker commands.
 *
 * @property serverId Persisted Local MCP server identifier associated with the failed command.
 * @property code Stable machine-readable worker/runtime failure code.
 * @property message Human-readable worker/runtime failure message.
 * @property details Optional additional diagnostics returned by worker runtime execution.
 */
@Serializable
data class WorkerMcpServerControlErrorResultData(
    val serverId: Long,
    val code: String,
    val message: String,
    val details: String? = null
)

