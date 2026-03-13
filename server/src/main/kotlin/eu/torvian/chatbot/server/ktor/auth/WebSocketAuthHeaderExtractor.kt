package eu.torvian.chatbot.server.ktor.auth

import eu.torvian.chatbot.common.api.CommonWebSocketProtocols
import io.ktor.http.HttpHeaders
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.request.ApplicationRequest
import org.apache.logging.log4j.LogManager

private val logger = LogManager.getLogger("WebSocketAuthHeaderExtractor")

private fun redactToken(token: String): String =
    if (token.length <= 20) token else "${token.take(10)}...(${token.length} chars)"

/**
 * Resolves a JWT auth header for Ktor authentication.
 *
 * Priority:
 * 1) `Authorization: Bearer ...`
 * 2) WebSocket fallback from `Sec-WebSocket-Protocol`
 */
fun ApplicationRequest.extractJwtAuthHeader(): HttpAuthHeader? {
    val header = parseAuthorizationHeader()
    if (header != null) {
        return header
    }

    val token = extractBearerTokenFromWebSocketSubprotocolHeader(
        headerValue = headers[HttpHeaders.SecWebSocketProtocol],
        expectedMarker = CommonWebSocketProtocols.CHATBOT_AUTH
    ) ?: return null

    logger.debug("Using JWT from Sec-WebSocket-Protocol, token=${redactToken(token)}")

    return HttpAuthHeader.Single("Bearer", token)
}

/**
 * Parses a JWT value from the WebSocket protocol header format `marker,jwt`.
 */
internal fun extractBearerTokenFromWebSocketSubprotocolHeader(
    headerValue: String?,
    expectedMarker: String
): String? {
    if (headerValue.isNullOrBlank()) {
        return null
    }

    val tokens = headerValue
        .split(',')
        .map { it.trim().trim('"') }
        .filter { it.isNotBlank() }

    val markerIndex = tokens.indexOfFirst { it.equals(expectedMarker, ignoreCase = true) }
    if (markerIndex < 0 || markerIndex + 1 >= tokens.size) {
        return null
    }

    val jwt = tokens[markerIndex + 1]
    if (jwt.contains(' ') || jwt.contains(',')) {
        logger.debug("Rejected malformed JWT in Sec-WebSocket-Protocol: '${redactToken(jwt)}'")
        return null
    }

    return jwt
}


