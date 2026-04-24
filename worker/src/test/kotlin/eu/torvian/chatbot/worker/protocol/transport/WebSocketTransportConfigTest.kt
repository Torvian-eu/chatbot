package eu.torvian.chatbot.worker.protocol.transport

import eu.torvian.chatbot.common.api.resources.workerWebSocketConnectPath
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies worker websocket URL generation continues to follow the established URL rules.
 */
class WebSocketTransportConfigTest {
    /**
     * Ensures HTTPS server bases resolve to a WSS URL targeting the shared worker connect path.
     */
    @Test
    fun fromServerBaseUrl_usesSharedWorkerConnectPath() {
        val config = WebSocketTransportConfig.fromServerBaseUrl(
            serverBaseUrl = "https://localhost:8443/",
            workerUid = "worker-1"
        )

        assertEquals("wss://localhost:8443${workerWebSocketConnectPath()}", config.webSocketUrl)
        assertEquals(10_000L, config.helloWelcomeTimeoutMs)
    }

    /**
     * Ensures any server base path segments remain part of the final WebSocket URL.
     */
    @Test
    fun fromServerBaseUrl_preservesServerBasePathSegments() {
        val config = WebSocketTransportConfig.fromServerBaseUrl(
            serverBaseUrl = "http://localhost:8080/relay/",
            workerUid = "worker-1"
        )

        assertEquals("ws://localhost:8080/relay${workerWebSocketConnectPath()}", config.webSocketUrl)
    }
}
