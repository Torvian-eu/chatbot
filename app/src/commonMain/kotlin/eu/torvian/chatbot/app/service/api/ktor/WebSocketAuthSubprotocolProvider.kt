package eu.torvian.chatbot.app.service.api.ktor

/**
 * Supplies WebSocket subprotocol values used during handshake authentication.
 *
 * Returning `null` or an empty list keeps the default transport behavior.
 */
fun interface WebSocketAuthSubprotocolProvider {
    suspend fun getSubprotocols(): List<String>?
}

