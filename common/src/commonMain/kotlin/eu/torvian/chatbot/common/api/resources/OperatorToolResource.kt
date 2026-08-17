package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.Resource

/**
 * Type-safe Ktor resource for `/api/v1/operator-tools` endpoints.
 *
 * Provides nested resource classes for listing the authenticated user's operator tools and for
 * interacting with a single operator tool by its tool-definition ID.
 *
 * @property parent The parent [Api] resource that anchors the `/api/v1` prefix.
 */
@Resource("operator-tools")
data class OperatorToolResource(
    val parent: Api = Api()
) {
    /**
     * Nested resource for `POST /api/v1/operator-tools/reset`.
     *
     * Triggers a reconciliation of the authenticated user's operator tool definitions with the
     * catalog defaults. Operator tools are per-user instances, so no user identifier is needed.
     *
     * @property parent The parent [OperatorToolResource].
     */
    @Resource("reset")
    data class Reset(
        val parent: OperatorToolResource = OperatorToolResource()
    )

    /**
     * Nested resource for `/api/v1/operator-tools/{toolId}`.
     *
     * Allows interacting with a single operator tool definition owned by the authenticated user,
     * such as toggling its enabled state.
     *
     * @property parent The parent [OperatorToolResource].
     * @property toolId The tool-definition identifier.
     */
    @Resource("{toolId}")
    data class ById(
        val parent: OperatorToolResource = OperatorToolResource(),
        val toolId: Long
    )
}
