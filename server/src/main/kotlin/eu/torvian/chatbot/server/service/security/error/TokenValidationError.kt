package eu.torvian.chatbot.server.service.security.error

/**
 * Sealed interface representing errors that can occur during JWT token validation.
 */
sealed interface TokenValidationError {
    /**
     * Token is invalid or malformed.
     */
    data object InvalidToken : TokenValidationError
    
    /**
     * Token has expired.
     */
    data object ExpiredToken : TokenValidationError
    
    /**
     * Token is malformed or cannot be parsed.
     */
    data object MalformedToken : TokenValidationError
    
    /**
     * Token signature is invalid.
     */
    data object InvalidSignature : TokenValidationError
    
    /**
     * Token audience or issuer is invalid.
     */
    data object InvalidClaims : TokenValidationError
    
    /**
     * User session associated with the token is invalid or expired.
     * 
     * @property reason Description of the session validation failure
     */
    data class InvalidSession(val reason: String) : TokenValidationError
}
