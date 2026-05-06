package eu.torvian.chatbot.server.service.security.error

/**
 * Sealed interface representing errors that can occur during single session logout.
 */
sealed interface LogoutError {
    /**
     * Session was not found.
     *
     * @property sessionId The session ID that was not found
     */
    data class SessionNotFound(val sessionId: Long) : LogoutError

    /**
     * The requester does not have permission to perform this logout operation.
     * Typically returned when a restricted session attempts to revoke another session.
     */
    data object InsufficientPermissions : LogoutError
}

/**
 * Sealed interface representing errors that can occur during logout from all sessions.
 */
sealed interface LogoutAllError {
    /**
     * User has no sessions to log out from.
     *
     * @property userId The user ID who has no active sessions
     */
    data class NoSessionsFound(val userId: Long) : LogoutAllError

    /**
     * The requester does not have permission to log out from all sessions.
     * Typically returned when a restricted session attempts to perform this action.
     */
    data object InsufficientPermissions : LogoutAllError
}
