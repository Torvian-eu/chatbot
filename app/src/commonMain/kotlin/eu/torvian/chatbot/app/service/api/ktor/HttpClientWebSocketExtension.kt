package eu.torvian.chatbot.app.service.api.ktor

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*

/**
 * Opens a WebSocket connection, dynamically choosing between `ws://` and `wss://`
 * based on the `wss` flag.
 *
 * This helper function corrects for the default Ktor `webSocket` behavior, which
 * always defaults to an insecure `ws://` connection.
 *
 * @param path The relative path for the WebSocket endpoint.
 * @param wss Whether to use a secure `wss://` connection.
 * @param request Optional block to configure the [HttpRequestBuilder].
 * @param block The block to execute within the [DefaultClientWebSocketSession].
 */
suspend fun HttpClient.webSocket(
    path: String,
    wss: Boolean,
    request: HttpRequestBuilder.() -> Unit = {},
    block: suspend DefaultClientWebSocketSession.() -> Unit
) {
    webSocket(
        request = {
            url {
                protocol = if (wss) URLProtocol.WSS else URLProtocol.WS
                path(path)
            }
            request()
        },
        block = block
    )
}