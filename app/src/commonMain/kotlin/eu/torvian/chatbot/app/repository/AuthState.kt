package eu.torvian.chatbot.app.repository

import eu.torvian.chatbot.common.models.Permission

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
     * @property permissions The list of permissions granted to the user (aggregated from all their roles)
     */
    data class Authenticated(
        val userId: Long,
        val username: String,
        val permissions: List<Permission>
    ) : AuthState()

    /**
     * Authentication operation is in progress.
     */
    object Loading : AuthState()
}