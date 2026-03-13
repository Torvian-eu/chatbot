package eu.torvian.chatbot.server.ktor.auth

import eu.torvian.chatbot.common.api.CommonWebSocketProtocols
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebSocketAuthHeaderExtractorTest {
    @Test
    fun `extractBearerTokenFromWebSocketSubprotocolHeader returns jwt when marker and token are present`() {
        val token = extractBearerTokenFromWebSocketSubprotocolHeader(
            headerValue = "${CommonWebSocketProtocols.CHATBOT_AUTH},eyJhbGciOiJIUzI1NiJ9.payload.signature",
            expectedMarker = CommonWebSocketProtocols.CHATBOT_AUTH
        )

        assertEquals("eyJhbGciOiJIUzI1NiJ9.payload.signature", token)
    }

    @Test
    fun `extractBearerTokenFromWebSocketSubprotocolHeader returns null when token is missing`() {
        val token = extractBearerTokenFromWebSocketSubprotocolHeader(
            headerValue = CommonWebSocketProtocols.CHATBOT_AUTH,
            expectedMarker = CommonWebSocketProtocols.CHATBOT_AUTH
        )

        assertNull(token)
    }

    @Test
    fun `extractBearerTokenFromWebSocketSubprotocolHeader returns null for malformed bearer value`() {
        val token = extractBearerTokenFromWebSocketSubprotocolHeader(
            headerValue = "${CommonWebSocketProtocols.CHATBOT_AUTH},Bearer broken",
            expectedMarker = CommonWebSocketProtocols.CHATBOT_AUTH
        )

        assertNull(token)
    }
}

