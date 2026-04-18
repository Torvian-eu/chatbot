package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/local-mcp-servers endpoints.
 *
 * These resources define type-safe routes for user-managed CRUD operations and
 * worker-assigned read operations for Local MCP server configurations.
 */
@Resource("local-mcp-servers")
class LocalMCPServerResource(val parent: Api = Api()) {
    /**
     * Transitional resource retained for compatibility with the previous API shape.
     */
    @Deprecated("Use LocalMCPServerResource root resource")
    @Resource("")
    class Create(val parent: LocalMCPServerResource = LocalMCPServerResource())

    /**
     * Resource for listing all Local MCP servers assigned to the authenticated worker.
     *
     * Endpoint: GET /api/v1/local-mcp-servers/assigned
     */
    @Resource("assigned")
    class Assigned(val parent: LocalMCPServerResource = LocalMCPServerResource())

    /**
     * Transitional resource retained for compatibility with the previous API shape.
     */
    @Deprecated("Use LocalMCPServerResource root resource")
    @Resource("ids")
    class Ids(val parent: LocalMCPServerResource = LocalMCPServerResource())

    /**
     * Resource for a specific Local MCP server by ID.
     *
     * Endpoint: /api/v1/local-mcp-servers/{id}
     */
    @Resource("{id}")
    class ById(val parent: LocalMCPServerResource = LocalMCPServerResource(), val id: Long) {
        /**
         * Resource for starting server runtime execution.
         *
         * Endpoint: POST /api/v1/local-mcp-servers/{id}/start
         */
        @Resource("start")
        class Start(val parent: ById)

        /**
         * Resource for stopping server runtime execution.
         *
         * Endpoint: POST /api/v1/local-mcp-servers/{id}/stop
         */
        @Resource("stop")
        class Stop(val parent: ById)

        /**
         * Resource for testing server runtime connectivity.
         *
         * Endpoint: POST /api/v1/local-mcp-servers/{id}/test-connection
         */
        @Resource("test-connection")
        class TestConnection(val parent: ById)

        /**
         * Resource for refreshing server tools through runtime control.
         *
         * Endpoint: POST /api/v1/local-mcp-servers/{id}/refresh-tools
         */
        @Resource("refresh-tools")
        class RefreshTools(val parent: ById)

        /**
         * Transitional resource retained for compatibility with the previous API shape.
         */
        @Deprecated("Use PUT on LocalMCPServerResource.ById")
        @Resource("enabled")
        class SetEnabled(val parent: ById)
    }
}

