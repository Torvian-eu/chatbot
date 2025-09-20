package eu.torvian.chatbot.server.service.security.error

/**
 * Sealed interface representing errors that can occur during token refresh.
 */
sealed interface RefreshTokenError {
    /**
     * Refresh token is invalid or malformed.
     */
    data object InvalidRefreshToken : RefreshTokenError
    
    /**
     * Refresh token has expired.
     */
    data object ExpiredRefreshToken : RefreshTokenError
    
    /**
     * User session associated with the refresh token is invalid.
     * 
     * @property reason Description of the session validation failure
     */
    data class InvalidSession(val reason: String) : RefreshTokenError
    
    /**
     * Failed to generate new tokens.
     * 
     * @property reason Description of the token generation failure
     */
    data class TokenGenerationFailed(val reason: String) : RefreshTokenError
}
