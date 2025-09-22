package eu.torvian.chatbot.server.service.security.error

/**
 * Sealed interface representing errors that can occur during user login.
 */
sealed interface LoginError {
    /**
     * Invalid credentials provided (wrong username or password).
     */
    data object InvalidCredentials : LoginError
    
    /**
     * User account was not found.
     */
    data object UserNotFound : LoginError
    
    /**
     * User account is locked or disabled.
     * 
     * @property reason Description of why the account is locked
     */
    data class AccountLocked(val reason: String) : LoginError
    
    /**
     * Session creation failed after successful authentication.
     * 
     * @property reason Description of the session creation failure
     */
    data class SessionCreationFailed(val reason: String) : LoginError
}
