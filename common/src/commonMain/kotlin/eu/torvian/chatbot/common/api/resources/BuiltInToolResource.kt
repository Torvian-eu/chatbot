package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.Resource

/**
 * Type-safe Ktor resource for `/api/v1/built-in-tools` endpoints.
 *
 * Provides nested resource classes for listing tools by worker ID (`ByWorkerId`)
 * and interacting with a specific tool by its tool-definition ID (`ById`).
 *
 * @property parent The parent [Api] resource that anchors the `/api/v1` prefix.
 */
@Resource("built-in-tools")
data class BuiltInToolResource(
    val parent: Api = Api()
) {
    /**
     * Nested resource for `/api/v1/built-in-tools/worker/{workerId}`.
     *
     * Returns all built-in worker tool definitions owned by a specific worker.
     * The authenticated user must be the owner of the worker.
     *
     * @property parent The parent [BuiltInToolResource].
     * @property workerId The identifier of the worker whose tools are being listed.
     */
    @Resource("worker/{workerId}")
    data class ByWorkerId(
        val parent: BuiltInToolResource = BuiltInToolResource(),
        val workerId: Long
    )

    /**
     * Nested resource for `POST /api/v1/built-in-tools/worker/{workerId}/reset`.
     *
     * Triggers a reconciliation of the worker's built-in tool definitions with the catalog
     * defaults. The authenticated user must be the owner of the worker.
     *
     * @property parent The parent [BuiltInToolResource].
     * @property workerId The identifier of the worker whose tools should be reset.
     */
    @Resource("worker/{workerId}/reset")
    data class ResetByWorkerId(
        val parent: BuiltInToolResource = BuiltInToolResource(),
        val workerId: Long
    )

    /**
     * Nested resource for `/api/v1/built-in-tools/{toolId}`.
     *
     * Allows interacting with a single built-in worker tool definition, such as
     * toggling its enabled state.
     *
     * @property parent The parent [BuiltInToolResource].
     * @property toolId The tool-definition identifier.
     */
    @Resource("{toolId}")
    data class ById(
        val parent: BuiltInToolResource = BuiltInToolResource(),
        val toolId: Long
    )
}
