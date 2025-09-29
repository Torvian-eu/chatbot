package eu.torvian.chatbot.app.repository

/**
 * Represents the current authentication state of the application.
 */
sealed class AuthState {
    /**
     * User is not authenticated (no valid tokens).
     */
    object Unauthenticated : AuthState()

    /**
     * User is authenticated with valid tokens.
     *
     * @property userId The unique identifier of the authenticated user
     * @property username The username of the authenticated user
     */
    data class Authenticated(
        val userId: Long,
        val username: String
    ) : AuthState()

    /**
     * Authentication operation is in progress.
     */
    object Loading : AuthState()
}