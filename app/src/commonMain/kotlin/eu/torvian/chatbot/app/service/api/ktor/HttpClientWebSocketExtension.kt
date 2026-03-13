package eu.torvian.chatbot.app.service.api.ktor

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders.SecWebSocketProtocol

/**
 * Opens a WebSocket connection, dynamically choosing between `ws://` and `wss://`
 * based on the `wss` flag.
 *
 * This helper function corrects for the default Ktor `webSocket` behavior, which
 * always defaults to an insecure `ws://` connection.
 *
 * @param path The relative path for the WebSocket endpoint.
 * @param wss Whether to use a secure `wss://` connection.
 * @param subprotocols Optional list of subprotocols to offer during handshake.
 * Values are written to `Sec-WebSocket-Protocol` as separate header values.
 * @param request Optional block to configure the [HttpRequestBuilder].
 * @param block The block to execute within the [DefaultClientWebSocketSession].
 */
suspend fun HttpClient.webSocket(
    path: String,
    wss: Boolean,
    subprotocols: List<String> = emptyList(),
    request: HttpRequestBuilder.() -> Unit = {},
    block: suspend DefaultClientWebSocketSession.() -> Unit
) {
    webSocket(
        request = {
            url {
                protocol = if (wss) URLProtocol.WSS else URLProtocol.WS
                path(path)
            }
            // Multiple values are valid and equivalent to a single comma-separated header.
            subprotocols.forEach { subprotocol ->
                header(SecWebSocketProtocol, subprotocol)
            }
            request()
        },
        block = block
    )
}