package eu.torvian.chatbot.common.api.resources

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that the shared worker websocket contract resolves to the expected canonical path.
 */
class WsResourceTest {
    /**
     * Confirms the typed worker connect resource expands to `/api/v1/ws/workers/connect`.
     */
    @Test
    fun workerWebSocketConnectResourceResolvesToCanonicalPath() {
        assertEquals("/api/v1/ws/workers/connect", href(WsResource.Workers.Connect()))
        assertEquals("/api/v1/ws/workers/connect", workerWebSocketConnectPath())
    }
}
