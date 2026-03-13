package eu.torvian.chatbot.common.api

/**
 * Shared WebSocket subprotocol names used by client and server.
 */
object CommonWebSocketProtocols {
    /**
     * Marker protocol used to negotiate browser-friendly auth fallback.
     */
    const val CHATBOT_AUTH = "chatbot-auth"
}

