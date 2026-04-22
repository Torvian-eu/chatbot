package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
 * Request data carried by `mcp.server.discover_tools` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier targeted for tool discovery.
 */
@Serializable
data class WorkerMcpServerDiscoverToolsCommandData(
    val serverId: Long
)

/**
 * Request data carried by `mcp.server.get_runtime_status` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier targeted for runtime status retrieval.
 */
@Serializable
data class WorkerMcpServerGetRuntimeStatusCommandData(
    val serverId: Long
)

/**
 * Request data carried by `mcp.server.list_runtime_statuses` worker commands.
 */
@Serializable
data object WorkerMcpServerListRuntimeStatusesCommandData

/**
 * Request data carried by `mcp.server.create` worker commands.
 *
 * @property server Persisted Local MCP server configuration to upsert in worker cache.
 */
@Serializable
data class WorkerMcpServerCreateCommandData(
    val server: LocalMCPServerDto
)

/**
 * Request data carried by `mcp.server.update` worker commands.
 *
 * @property server Persisted Local MCP server configuration to upsert in worker cache.
 */
@Serializable
data class WorkerMcpServerUpdateCommandData(
    val server: LocalMCPServerDto
)

/**
 * Request data carried by `mcp.server.delete` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier to remove from worker cache.
 */
@Serializable
data class WorkerMcpServerDeleteCommandData(
    val serverId: Long
)

/**
 * Runtime tool metadata returned by worker-side MCP discovery.
 *
 * @property name Raw MCP tool name returned by runtime discovery.
 * @property description Optional MCP tool description.
 * @property inputSchema MCP tool input JSON schema.
 * @property outputSchema Optional MCP tool output JSON schema.
 */
@Serializable
data class WorkerMcpDiscoveredToolData(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null
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
 * Result data emitted for `mcp.server.discover_tools` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier that was queried.
 * @property tools Runtime-discovered MCP tool metadata for this server.
 */
@Serializable
data class WorkerMcpServerDiscoverToolsResultData(
    val serverId: Long,
    val tools: List<WorkerMcpDiscoveredToolData>
)

/**
 * Result data emitted for `mcp.server.get_runtime_status` worker commands.
 *
 * @property status Runtime status snapshot for the requested server.
 */
@Serializable
data class WorkerMcpServerGetRuntimeStatusResultData(
    val status: LocalMcpServerRuntimeStatusDto
)

/**
 * Result data emitted for `mcp.server.list_runtime_statuses` worker commands.
 *
 * @property statuses Runtime status snapshots for all worker-visible servers.
 */
@Serializable
data class WorkerMcpServerListRuntimeStatusesResultData(
    val statuses: List<LocalMcpServerRuntimeStatusDto>
)

/**
 * Result data emitted for successful `mcp.server.create` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier that was upserted in worker cache.
 */
@Serializable
data class WorkerMcpServerCreateResultData(
    val serverId: Long
)

/**
 * Result data emitted for successful `mcp.server.update` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier that was upserted in worker cache.
 */
@Serializable
data class WorkerMcpServerUpdateResultData(
    val serverId: Long
)

/**
 * Result data emitted for successful `mcp.server.delete` worker commands.
 *
 * @property serverId Persisted Local MCP server identifier that was removed from worker cache.
 */
@Serializable
data class WorkerMcpServerDeleteResultData(
    val serverId: Long
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

