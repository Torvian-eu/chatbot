package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.Resource

/**
 * Resource definitions for websocket-specific API endpoints under `/api/v1/ws`.
 *
 * This namespace keeps websocket routes separate from the regular REST resource tree so
 * transport-specific endpoints can evolve without making the main HTTP resource model awkward.
 *
 * @property parent Parent API resource (`/api/v1`).
 */
@Resource("ws")
class WsResource(val parent: Api = Api()) {
    /**
     * Resource group for worker websocket endpoints under `/api/v1/ws/workers`.
     *
     * @property parent Parent websocket resource.
     */
    @Resource("workers")
    class Workers(val parent: WsResource = WsResource()) {
        /**
         * Worker websocket connect endpoint at `/api/v1/ws/workers/connect`.
         *
         * This resource is the shared source of truth for the worker transport connect route.
         *
         * @property parent Parent worker websocket resource.
         */
        @Resource("connect")
        class Connect(val parent: Workers = Workers())
    }
}

/**
 * Returns the canonical path for the worker websocket connect endpoint.
 *
 * The value is derived from the typed shared resource contract so both server and worker code
 * resolve the same route path without duplicating the literal string.
 *
 * @return The canonical `/api/v1/ws/workers/connect` path.
 */
fun workerWebSocketConnectPath(): String = href(WsResource.Workers.Connect())
