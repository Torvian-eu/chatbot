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
     * Login was blocked because the client connected from an unrecognized device.
     *
     * @property userId The ID of the user who attempted to log in from an unrecognized device.
     */
    data class VerificationRequired(val userId: Long) : LoginError

    /**
     * Login was blocked due to too many failed attempts within the configured window.
     *
     * This implements a sliding-window lockout policy (e.g., 10 failures per 5 minutes)
     * based on both username and IP address to prevent brute-force attacks.
     */
    data object TooManyAttempts : LoginError

    /**
     * Session creation failed after successful authentication.
     * 
     * @property reason Description of the session creation failure
     */
    data class SessionCreationFailed(val reason: String) : LoginError
}
