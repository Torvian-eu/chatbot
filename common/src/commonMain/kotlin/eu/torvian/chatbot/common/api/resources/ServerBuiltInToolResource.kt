package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.Resource

/**
 * Type-safe Ktor resource for `/api/v1/server-built-in-tools` endpoints.
 *
 * Provides nested resource classes for listing the authenticated user's server built-in tools and
 * for interacting with a single server built-in tool by its tool-definition ID.
 *
 * @property parent The parent [Api] resource that anchors the `/api/v1` prefix.
 */
@Resource("server-built-in-tools")
data class ServerBuiltInToolResource(
    val parent: Api = Api()
) {
    /**
     * Nested resource for `POST /api/v1/server-built-in-tools/reset`.
     *
     * Triggers a reconciliation of the authenticated user's server built-in tool definitions with
     * the catalog defaults. Server built-in tools are per-user instances, so no user identifier is
     * needed.
     *
     * @property parent The parent [ServerBuiltInToolResource].
     */
    @Resource("reset")
    data class Reset(
        val parent: ServerBuiltInToolResource = ServerBuiltInToolResource()
    )

    /**
     * Nested resource for `/api/v1/server-built-in-tools/{toolId}`.
     *
     * Allows interacting with a single server built-in tool definition owned by the authenticated
     * user, such as toggling its enabled state.
     *
     * @property parent The parent [ServerBuiltInToolResource].
     * @property toolId The tool-definition identifier.
     */
    @Resource("{toolId}")
    data class ById(
        val parent: ServerBuiltInToolResource = ServerBuiltInToolResource(),
        val toolId: Long
    )
}
