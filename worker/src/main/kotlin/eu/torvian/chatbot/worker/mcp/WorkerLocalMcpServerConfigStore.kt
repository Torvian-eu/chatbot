package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import java.util.concurrent.ConcurrentHashMap

/**
 * Read/write store for worker-assigned local MCP server configurations.
 *
 * This abstraction allows the runtime layer to resolve server configuration without coupling to
 * the transport bootstrap mechanism that eventually hydrates assignment state.
 */
interface WorkerLocalMcpServerConfigStore {
    /**
     * Returns a server configuration by identifier.
     *
     * @param serverId Persisted local MCP server identifier.
     * @return Assigned server configuration or null when no assignment exists.
     */
    suspend fun getServer(serverId: Long): LocalMCPServerDto?

    /**
     * Inserts or replaces one server configuration in the store.
     *
     * @param config Server configuration to cache.
     */
    suspend fun upsertServer(config: LocalMCPServerDto)

    /**
     * Removes one server configuration from the store.
     *
     * @param serverId Persisted local MCP server identifier to remove.
     */
    suspend fun removeServer(serverId: Long)

    /**
     * Replaces the entire cached assignment set.
     *
     * @param servers Full worker-assigned server set.
     */
    suspend fun replaceAll(servers: List<LocalMCPServerDto>)
}

/**
 * In-memory [WorkerLocalMcpServerConfigStore] used until assigned-server bootstrap sync is wired.
 */
class InMemoryWorkerLocalMcpServerConfigStore : WorkerLocalMcpServerConfigStore {
    /**
     * Concurrent cache keyed by server identifier.
     */
    private val serverById: ConcurrentHashMap<Long, LocalMCPServerDto> = ConcurrentHashMap()

    /**
     * @param serverId Persisted local MCP server identifier.
     * @return Cached server configuration or null.
     */
    override suspend fun getServer(serverId: Long): LocalMCPServerDto? = serverById[serverId]

    /**
     * @param config Server configuration to cache.
     */
    override suspend fun upsertServer(config: LocalMCPServerDto) {
        serverById[config.id] = config
    }

    /**
     * @param serverId Persisted local MCP server identifier to remove.
     */
    override suspend fun removeServer(serverId: Long) {
        serverById.remove(serverId)
    }

    /**
     * @param servers Full worker-assigned server set.
     */
    override suspend fun replaceAll(servers: List<LocalMCPServerDto>) {
        serverById.clear()
        servers.forEach { server ->
            serverById[server.id] = server
        }
    }
}

