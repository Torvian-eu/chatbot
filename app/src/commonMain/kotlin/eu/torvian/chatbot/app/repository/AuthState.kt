package eu.torvian.chatbot.app.repository

import eu.torvian.chatbot.common.models.user.Permission

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
     * @property requiresPasswordChange Whether the user must change their password immediately
     * @property isRestricted Whether the session is restricted (created from an unacknowledged IP)
     */
    data class Authenticated(
        val userId: Long,
        val username: String,
        val permissions: List<Permission>,
        val requiresPasswordChange: Boolean = false,
        val isRestricted: Boolean = false
    ) : AuthState()

    /**
     * Authentication operation is in progress.
     */
    object Loading : AuthState()
}