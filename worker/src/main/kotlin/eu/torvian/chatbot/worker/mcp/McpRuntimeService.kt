package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import kotlinx.serialization.json.JsonObject

/**
 * Runtime-facing service for worker MCP server lifecycle and discovery operations.
 *
 * This service owns server-config lookup and MCP runtime orchestration while remaining protocol-agnostic.
 */
interface McpRuntimeService {
    /**
     * Starts a configured local MCP server runtime.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or Unit.
     */
    suspend fun startServer(serverId: Long): Either<McpRuntimeError, Unit>

    /**
     * Stops a configured local MCP server runtime.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or Unit.
     */
    suspend fun stopServer(serverId: Long): Either<McpRuntimeError, Unit>

    /**
     * Verifies MCP runtime connectivity and tool discovery for one configured server.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or test-connection outcome.
     */
    suspend fun testConnection(serverId: Long): Either<McpRuntimeError, McpTestConnectionOutcome>

    /**
     * Performs runtime tool discovery for one configured server.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or discovered tool metadata.
     */
    suspend fun discoverTools(serverId: Long): Either<McpRuntimeError, List<McpDiscoveredTool>>

    /**
     * Returns a runtime status snapshot for one configured local MCP server.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or runtime status snapshot DTO.
     */
    suspend fun getRuntimeStatus(serverId: Long): Either<McpRuntimeError, LocalMcpServerRuntimeStatusDto>

    /**
     * Returns runtime status snapshots for all worker-assigned local MCP servers.
     *
     * @return Runtime status snapshots keyed by assignment membership.
     */
    suspend fun listRuntimeStatuses(): List<LocalMcpServerRuntimeStatusDto>

    /**
     * Calls a tool on a configured local MCP server.
     *
     * Handles server config resolution, ensures runtime/client connection exists,
     * invokes the tool through MCP SDK, and maps the result to a runtime-level outcome.
     *
     * @param serverId Persisted local MCP server identifier.
     * @param toolName MCP tool name to invoke.
     * @param arguments JSON argument object passed to the tool.
     * @return Either runtime error or tool-call outcome.
     */
    suspend fun callTool(
        serverId: Long,
        toolName: String,
        arguments: JsonObject
    ): Either<McpRuntimeError, McpToolCallOutcome?>

    /**
     * Verifies MCP runtime connectivity and tool discovery for a draft server configuration.
     *
     * @param config Draft local MCP server configuration.
     * @return Either runtime error or test-connection outcome.
     */
    suspend fun testDraftConnection(config: LocalMCPServerDto): Either<McpRuntimeError, McpTestConnectionOutcome>
}
