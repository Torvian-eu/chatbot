package eu.torvian.chatbot.server.service.security.error

/**
 * Error types for security alerts retrieval operations.
 */
sealed interface GetSecurityAlertsError {
    /**
     * Raised when a restricted session attempts to list security alerts.
     *
     * @property reason Human-readable description of why the action was denied
     */
    data class InsufficientPermissions(val reason: String = "Action requires a trusted session") : GetSecurityAlertsError
}
