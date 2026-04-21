package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto

/**
 * Read/write store for worker-assigned local MCP server configurations.
 *
 * This abstraction allows the runtime layer to resolve server configuration without coupling to
 * the transport bootstrap mechanism that eventually hydrates assignment state.
 */
interface McpServerConfigStore {
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

    /**
     * Returns all currently cached worker-assigned server configurations.
     *
     * @return Snapshot list of assigned server configurations.
     */
    suspend fun listServers(): List<LocalMCPServerDto>
}

