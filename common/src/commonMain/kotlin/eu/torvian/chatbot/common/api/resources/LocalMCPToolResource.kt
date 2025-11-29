package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/local-mcp-tools endpoints.
 *
 * These resources define type-safe routes for managing Local MCP Tool definitions.
 */
@Resource("local-mcp-tools")
class LocalMCPToolResource(val parent: Api = Api()) {
    /**
     * Resource for batch creating MCP tools: /api/v1/local-mcp-tools/batch
     */
    @Resource("batch")
    class Batch(val parent: LocalMCPToolResource = LocalMCPToolResource())

    /**
     * Resource for refreshing MCP tools: /api/v1/local-mcp-tools/refresh
     */
    @Resource("refresh")
    class Refresh(val parent: LocalMCPToolResource = LocalMCPToolResource())

    /**
     * Resource for getting all tools for a server: /api/v1/local-mcp-tools/server/{serverId}
     */
    @Resource("server/{serverId}")
    class ByServerId(val parent: LocalMCPToolResource = LocalMCPToolResource(), val serverId: Long) {
        /**
         * Resource for deleting all tools for a server: DELETE /api/v1/local-mcp-tools/server/{serverId}
         */
        // Same resource, different HTTP method
    }

    /**
     * Resource for a specific tool by ID: /api/v1/local-mcp-tools/{toolId}
     */
    @Resource("{toolId}")
    class ById(val parent: LocalMCPToolResource = LocalMCPToolResource(), val toolId: Long)
}

