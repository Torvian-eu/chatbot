package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.Resource

/**
 * Resource definitions for the `/api/v1/search` endpoints.
 *
 * @property parent Parent API resource anchoring this subtree under `/api/v1`.
 */
@Resource("search")
class SearchResource(
    /**
     * Parent API resource anchoring this subtree under `/api/v1`.
     */
    val parent: Api = Api()
) {
    /**
     * Resource for cross-session message search requests at `/api/v1/search/messages`.
     *
     * @property parent Parent search resource.
     * @property query Search string supplied by the client as a query parameter.
     */
    @Resource("messages")
    class Messages(
        val parent: SearchResource = SearchResource(),
        val query: String
    )
}