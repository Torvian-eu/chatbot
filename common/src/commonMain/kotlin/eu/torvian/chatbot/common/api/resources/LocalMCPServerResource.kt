package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/local-mcp-servers endpoints.
 *
 * These resources define type-safe routes for managing Local MCP Server IDs.
 * Note: The server only manages ID generation and ownership tracking.
 * Full MCP server configurations are stored client-side.
 */
@Resource("local-mcp-servers")
class LocalMCPServerResource(val parent: Api = Api()) {
    /**
     * Resource for generating a new Local MCP server ID: POST /api/v1/local-mcp-servers/generate-id
     */
    @Resource("generate-id")
    class GenerateId(val parent: LocalMCPServerResource = LocalMCPServerResource())

    /**
     * Resource for listing all server IDs for a user: GET /api/v1/local-mcp-servers/ids
     */
    @Resource("ids")
    class Ids(val parent: LocalMCPServerResource = LocalMCPServerResource())

    /**
     * Resource for a specific Local MCP server by ID: /api/v1/local-mcp-servers/{id}
     */
    @Resource("{id}")
    class ById(val parent: LocalMCPServerResource = LocalMCPServerResource(), val id: Long)
}

