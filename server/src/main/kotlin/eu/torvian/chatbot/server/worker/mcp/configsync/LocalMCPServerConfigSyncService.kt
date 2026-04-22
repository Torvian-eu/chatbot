package eu.torvian.chatbot.server.worker.mcp.configsync

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto

/**
 * Coordinates best-effort propagation of Local MCP server configuration changes to the assigned worker.
 *
 * The contract keeps orchestration policy in one place so the HTTP layer can remain transport-focused
 * while the implementation decides whether an update is a same-worker patch or a worker reassignment.
 */
interface LocalMCPServerConfigSyncService {
    /**
     * Pushes a newly created Local MCP server configuration to the assigned worker cache.
     *
     * @param server Newly created Local MCP server configuration.
     */
    suspend fun syncCreated(server: LocalMCPServerDto)

    /**
     * Pushes an updated Local MCP server configuration to the assigned worker cache.
     *
     * Same-worker updates are forwarded as in-place worker updates. When the worker changes,
     * the stale cache entry is removed from the previous worker before creating the server on the new worker
     * so the worker cache does not keep serving the old assignment.
     *
     * @param server Updated Local MCP server configuration after the update was persisted.
     * @param previousWorkerId Worker identifier that owned the server before the update.
     */
    suspend fun syncUpdated(server: LocalMCPServerDto, previousWorkerId: Long)

    /**
     * Removes a deleted Local MCP server configuration from the assigned worker cache.
     *
     * @param workerId Worker identifier that previously owned the server configuration.
     * @param serverId Local MCP server identifier to remove.
     */
    suspend fun syncDeleted(workerId: Long, serverId: Long)
}

