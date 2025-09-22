package eu.torvian.chatbot.server.service.core.error.auth

/**
 * Sealed interface representing errors that can occur during user registration.
 */
sealed interface RegisterUserError {
    /**
     * Username already exists in the system.
     * 
     * @property username The username that already exists
     */
    data class UsernameAlreadyExists(val username: String) : RegisterUserError
    
    /**
     * Email address already exists in the system.
     * 
     * @property email The email that already exists
     */
    data class EmailAlreadyExists(val email: String) : RegisterUserError
    
    /**
     * Invalid input provided during registration.
     * 
     * @property reason Description of what input was invalid
     */
    data class InvalidInput(val reason: String) : RegisterUserError
    
    /**
     * Password does not meet strength requirements.
     * 
     * @property reason Description of why the password is too weak
     */
    data class PasswordTooWeak(val reason: String) : RegisterUserError
    
    /**
     * Failed to add user to the "All Users" group during registration.
     * 
     * @property reason Description of the failure
     */
    data class GroupAssignmentFailed(val reason: String) : RegisterUserError
}
