package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/local-mcp-servers endpoints.
 *
 * These resources define type-safe routes for managing Local MCP Server creation,
 * deletion, and state synchronization.
 * Note: The server only manages ID generation, ownership tracking, and enabled state.
 * Full MCP server configurations are stored client-side.
 */
@Resource("local-mcp-servers")
class LocalMCPServerResource(val parent: Api = Api()) {
    /**
     * Resource for creating a new Local MCP server: POST /api/v1/local-mcp-servers
     */
    @Resource("")
    class Create(val parent: LocalMCPServerResource = LocalMCPServerResource())

    /**
     * Resource for listing all server IDs for a user: GET /api/v1/local-mcp-servers/ids
     */
    @Resource("ids")
    class Ids(val parent: LocalMCPServerResource = LocalMCPServerResource())

    /**
     * Resource for a specific Local MCP server by ID: /api/v1/local-mcp-servers/{id}
     */
    @Resource("{id}")
    class ById(val parent: LocalMCPServerResource = LocalMCPServerResource(), val id: Long) {
        /**
         * Resource for updating enabled state: PUT /api/v1/local-mcp-servers/{id}/enabled
         */
        @Resource("enabled")
        class SetEnabled(val parent: ById)
    }
}

