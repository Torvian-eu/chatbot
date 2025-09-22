package eu.torvian.chatbot.server.service.core.error.auth

/**
 * Sealed interface representing errors when a user cannot be found.
 */
sealed interface UserNotFoundError {
    /**
     * User with the specified ID was not found.
     * 
     * @property id The user ID that was not found
     */
    data class ById(val id: Long) : UserNotFoundError
    
    /**
     * User with the specified username was not found.
     * 
     * @property username The username that was not found
     */
    data class ByUsername(val username: String) : UserNotFoundError
}
