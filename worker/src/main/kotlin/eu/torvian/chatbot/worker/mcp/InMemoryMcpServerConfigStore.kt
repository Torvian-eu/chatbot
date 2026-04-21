package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [McpServerConfigStore] used until assigned-server bootstrap sync is wired.
 */
class InMemoryMcpServerConfigStore : McpServerConfigStore {
    /**
     * Concurrent cache keyed by server identifier.
     */
    private val serverById: ConcurrentHashMap<Long, LocalMCPServerDto> = ConcurrentHashMap()

    override suspend fun getServer(serverId: Long): LocalMCPServerDto? = serverById[serverId]

    override suspend fun upsertServer(config: LocalMCPServerDto) {
        serverById[config.id] = config
    }

    override suspend fun removeServer(serverId: Long) {
        serverById.remove(serverId)
    }

    override suspend fun replaceAll(servers: List<LocalMCPServerDto>) {
        serverById.clear()
        servers.forEach { server ->
            serverById[server.id] = server
        }
    }

    override suspend fun listServers(): List<LocalMCPServerDto> = serverById.values.toList()
}