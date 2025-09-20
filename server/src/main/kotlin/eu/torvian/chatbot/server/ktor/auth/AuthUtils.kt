package eu.torvian.chatbot.server.ktor.auth

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

/**
 * Utility functions for extracting user context from authenticated requests.
 * 
 * These functions provide a convenient way to extract user information from JWT tokens
 * in authenticated Ktor routes. They assume the request has been authenticated using
 * the JWT authentication scheme.
 */

/**
 * Extracts the user ID from the authenticated JWT token.
 * 
 * @return The user ID from the token's subject claim
 * @throws IllegalStateException if the user ID is not found or invalid
 */
fun ApplicationCall.getUserId(): Long {
    val principal = principal<JWTPrincipal>()
        ?: throw IllegalStateException("No JWT principal found - ensure route is protected with authentication")
    
    val userId = principal.payload.subject?.toLongOrNull()
        ?: throw IllegalStateException("User ID not found in JWT token or is invalid")
    
    return userId
}

/**
 * Extracts the session ID from the authenticated JWT token.
 * 
 * @return The session ID from the token's sessionId claim
 * @throws IllegalStateException if the session ID is not found or invalid
 */
fun ApplicationCall.getSessionId(): Long {
    val principal = principal<JWTPrincipal>()
        ?: throw IllegalStateException("No JWT principal found - ensure route is protected with authentication")
    
    val sessionId = principal.payload.getClaim("sessionId")?.asLong()
        ?: throw IllegalStateException("Session ID not found in JWT token or is invalid")
    
    return sessionId
}

/**
 * Extracts the token type from the authenticated JWT token.
 * 
 * This is useful for distinguishing between access tokens and refresh tokens.
 * 
 * @return The token type from the token's tokenType claim, or "access" if not specified
 */
fun ApplicationCall.getTokenType(): String {
    val principal = principal<JWTPrincipal>()
        ?: throw IllegalStateException("No JWT principal found - ensure route is protected with authentication")
    
    return principal.payload.getClaim("tokenType")?.asString() ?: "access"
}

/**
 * Checks if the current token is a refresh token.
 * 
 * @return true if the token is a refresh token, false otherwise
 */
fun ApplicationCall.isRefreshToken(): Boolean {
    return getTokenType() == "refresh"
}

/**
 * Extracts the JWT payload for custom claim access.
 * 
 * This provides direct access to the JWT payload for extracting custom claims
 * that are not covered by the convenience methods above.
 * 
 * @return The JWT payload
 * @throws IllegalStateException if no JWT principal is found
 */
fun ApplicationCall.getJwtPayload(): com.auth0.jwt.interfaces.Payload {
    val principal = principal<JWTPrincipal>()
        ?: throw IllegalStateException("No JWT principal found - ensure route is protected with authentication")
    
    return principal.payload
}
