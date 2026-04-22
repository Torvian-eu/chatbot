package eu.torvian.chatbot.server.worker.mcp.configsync

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchService
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default best-effort implementation that forwards Local MCP config changes to the worker protocol layer.
 *
 * @property localMCPRuntimeCommandDispatchService Worker command adapter used to transport cache-sync requests.
 */
class DefaultLocalMCPServerConfigSyncService(
    private val localMCPRuntimeCommandDispatchService: LocalMCPRuntimeCommandDispatchService
) : LocalMCPServerConfigSyncService {
    override suspend fun syncCreated(server: LocalMCPServerDto) {
        localMCPRuntimeCommandDispatchService.createServer(workerId = server.workerId, server = server).fold(
            ifLeft = { error ->
                logger.warn(
                    "Failed to sync created MCP server {} to worker {}: {}",
                    server.id,
                    server.workerId,
                    error
                )
            },
            ifRight = {}
        )
    }

    /**
     * Propagates an updated Local MCP server to worker cache while handling worker reassignment.
     *
     * Same-worker updates are forwarded as an in-place worker update. When the worker changes,
     * the stale cache entry is removed from the previous worker before creating the server on the
     * new worker so the worker cache does not keep serving the old assignment.
     *
     * @param server Local MCP server configuration after persistence.
     * @param previousWorkerId Worker identifier that owned the server before persistence.
     */
    override suspend fun syncUpdated(server: LocalMCPServerDto, previousWorkerId: Long) {
        if (previousWorkerId == server.workerId) {
            localMCPRuntimeCommandDispatchService.updateServer(workerId = server.workerId, server = server).fold(
                ifLeft = { error ->
                    logger.warn(
                        "Failed to sync updated MCP server {} to worker {}: {}",
                        server.id,
                        server.workerId,
                        error
                    )
                },
                ifRight = {}
            )
            return
        }

        // Reassignment must remove the old cache entry first so the previous worker cannot keep serving stale config.
        localMCPRuntimeCommandDispatchService.deleteServer(
            workerId = previousWorkerId,
            serverId = server.id
        ).fold(
            ifLeft = { error ->
                logger.warn(
                    "Failed to remove reassigned MCP server {} from previous worker {}: {}",
                    server.id,
                    previousWorkerId,
                    error
                )
            },
            ifRight = {}
        )

        // Creation on the new worker remains best-effort even if cleanup on the old worker failed.
        localMCPRuntimeCommandDispatchService.createServer(workerId = server.workerId, server = server).fold(
            ifLeft = { error ->
                logger.warn(
                    "Failed to create reassigned MCP server {} on worker {}: {}",
                    server.id,
                    server.workerId,
                    error
                )
            },
            ifRight = {}
        )
    }

    override suspend fun syncDeleted(workerId: Long, serverId: Long) {
        localMCPRuntimeCommandDispatchService.deleteServer(workerId = workerId, serverId = serverId).fold(
            ifLeft = { error ->
                logger.warn(
                    "Failed to sync deleted MCP server {} to worker {}: {}",
                    serverId,
                    workerId,
                    error
                )
            },
            ifRight = {}
        )
    }

    companion object {
        /**
         * Logger used for best-effort Local MCP config sync diagnostics.
         */
        private val logger: Logger = LogManager.getLogger(DefaultLocalMCPServerConfigSyncService::class.java)
    }
}


