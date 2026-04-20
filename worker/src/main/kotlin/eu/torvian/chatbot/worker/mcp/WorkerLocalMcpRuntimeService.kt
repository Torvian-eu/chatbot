package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import kotlinx.serialization.json.JsonObject

/**
 * Runtime-facing service for worker MCP server lifecycle and discovery operations.
 *
 * This service owns server-config lookup and MCP runtime orchestration while remaining protocol-agnostic.
 */
interface WorkerLocalMcpRuntimeService {
    /**
     * Starts a configured local MCP server runtime.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or Unit.
     */
    suspend fun startServer(serverId: Long): Either<WorkerLocalMcpRuntimeError, Unit>

    /**
     * Stops a configured local MCP server runtime.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or Unit.
     */
    suspend fun stopServer(serverId: Long): Either<WorkerLocalMcpRuntimeError, Unit>

    /**
     * Verifies MCP runtime connectivity and tool discovery for one configured server.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or test-connection outcome.
     */
    suspend fun testConnection(serverId: Long): Either<WorkerLocalMcpRuntimeError, WorkerLocalMcpTestConnectionOutcome>

    /**
     * Performs runtime tool discovery for one configured server.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or discovered tool metadata.
     */
    suspend fun discoverTools(serverId: Long): Either<WorkerLocalMcpRuntimeError, List<WorkerLocalMcpDiscoveredTool>>

    /**
     * Returns a runtime status snapshot for one configured local MCP server.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Either runtime error or runtime status snapshot DTO.
     */
    suspend fun getRuntimeStatus(serverId: Long): Either<WorkerLocalMcpRuntimeError, LocalMcpServerRuntimeStatusDto>

    /**
     * Returns runtime status snapshots for all worker-assigned local MCP servers.
     *
     * @return Runtime status snapshots keyed by assignment membership.
     */
    suspend fun listRuntimeStatuses(): List<LocalMcpServerRuntimeStatusDto>
}

/**
 * Runtime-level test-connection outcome.
 *
 * @property discoveredToolCount Number of tools discovered during the connectivity check.
 * @property message Optional operator-facing message about the result.
 */
data class WorkerLocalMcpTestConnectionOutcome(
    val discoveredToolCount: Int,
    val message: String? = null
)

/**
 * Runtime-level representation of one discovered MCP tool.
 *
 * @property name Raw MCP tool name returned by runtime discovery.
 * @property description Optional MCP tool description.
 * @property inputSchema MCP tool input JSON schema.
 * @property outputSchema Optional MCP tool output JSON schema.
 */
data class WorkerLocalMcpDiscoveredTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null
)

/**
 * Logical runtime errors for worker MCP server control operations.
 */
sealed interface WorkerLocalMcpRuntimeError {
    /**
     * Stable machine-readable runtime error code.
     */
    val code: String

    /**
     * Human-readable runtime error message.
     */
    val message: String

    /**
     * Optional structured runtime diagnostics rendered as a simple string.
     */
    val details: String?

    /**
     * No configuration was available for the requested server identifier.
     *
     * @property serverId Persisted local MCP server identifier.
     */
    data class ServerConfigMissing(
        val serverId: Long
    ) : WorkerLocalMcpRuntimeError {
        /**
         * Stable machine-readable runtime error code.
         */
        override val code: String = "SERVER_CONFIG_MISSING"

        /**
         * Human-readable runtime error message.
         */
        override val message: String = "No local MCP server config available for serverId=$serverId"

        /**
         * Optional diagnostics for this runtime error.
         */
        override val details: String = "The worker config store has no entry for this server"
    }

    /**
     * Runtime start operation failed.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property message Human-readable runtime error message.
     * @property details Optional diagnostics.
     */
    data class StartFailed(
        val serverId: Long,
        override val message: String,
        override val details: String? = null
    ) : WorkerLocalMcpRuntimeError {
        /**
         * Stable machine-readable runtime error code.
         */
        override val code: String = "START_FAILED"
    }

    /**
     * Runtime stop operation failed.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property message Human-readable runtime error message.
     * @property details Optional diagnostics.
     */
    data class StopFailed(
        val serverId: Long,
        override val message: String,
        override val details: String? = null
    ) : WorkerLocalMcpRuntimeError {
        /**
         * Stable machine-readable runtime error code.
         */
        override val code: String = "STOP_FAILED"
    }

    /**
     * Runtime tool-discovery operation failed.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property message Human-readable runtime error message.
     * @property details Optional diagnostics.
     */
    data class DiscoveryFailed(
        val serverId: Long,
        override val message: String,
        override val details: String? = null
    ) : WorkerLocalMcpRuntimeError {
        /**
         * Stable machine-readable runtime error code.
         */
        override val code: String = "DISCOVERY_FAILED"
    }

    /**
     * Test-connection cleanup failed after a temporary start.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property message Human-readable runtime error message.
     * @property details Optional diagnostics.
     */
    data class CleanupFailed(
        val serverId: Long,
        override val message: String,
        override val details: String? = null
    ) : WorkerLocalMcpRuntimeError {
        /**
         * Stable machine-readable runtime error code.
         */
        override val code: String = "CLEANUP_FAILED"
    }
}

