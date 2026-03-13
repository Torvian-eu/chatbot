package eu.torvian.chatbot.app.service.api.ktor

import eu.torvian.chatbot.app.service.auth.TokenStorage
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.api.CommonWebSocketProtocols

/**
 * Browser-only provider that sends auth data through WebSocket subprotocols.
 *
 * Browsers do not allow custom handshake headers (like `Authorization`) for WebSockets.
 * To support authenticated connections on WasmJs, we offer:
 * 1) a marker subprotocol (`${CommonWebSocketProtocols.CHATBOT_AUTH}`) used for negotiation
 * 2) the JWT token as the next subprotocol value for server-side fallback auth parsing
 *
 * @property tokenStorage The token storage to retrieve the JWT token from.
 */
class BrowserWebSocketAuthSubprotocolProvider(
    private val tokenStorage: TokenStorage
) : WebSocketAuthSubprotocolProvider {
    private val logger = kmpLogger<BrowserWebSocketAuthSubprotocolProvider>()

    override suspend fun getSubprotocols(): List<String>? = tokenStorage.getAccessToken().fold(
        ifLeft = { error ->
            logger.warn("Unable to load access token for WebSocket handshake: ${error.message}")
            null
        },
        ifRight = { token ->
            if (token.isBlank()) {
                logger.warn("Access token for WebSocket handshake is blank")
                null
            } else {
                listOf(CommonWebSocketProtocols.CHATBOT_AUTH, token)
            }
        }
    )
}

