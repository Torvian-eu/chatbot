package eu.torvian.chatbot.server.service.security.error

/**
 * Sealed interface representing errors that can occur during user logout.
 */
sealed interface LogoutError {
    /**
     * User session was not found.
     * 
     * @property userId The user ID whose session was not found
     */
    data class SessionNotFound(val userId: Long) : LogoutError
    
    /**
     * Failed to invalidate the user session.
     * 
     * @property reason Description of the failure
     */
    data class SessionInvalidationFailed(val reason: String) : LogoutError
}
