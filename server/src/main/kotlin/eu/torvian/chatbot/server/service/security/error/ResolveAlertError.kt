package eu.torvian.chatbot.server.service.security.error

/**
 * Error types for security alert resolution operations.
 */
sealed class ResolveAlertError {
    /**
     * Raised when a restricted session attempts to resolve a security alert.
     *
     * @property reason Human-readable description of why the action was denied
     */
    data class InsufficientPermissions(val reason: String = "Action requires a trusted session") : ResolveAlertError()

    /**
     * Raised when the specified alert is not found.
     *
     * @property alertId The identifier of the alert that was not found
     */
    data class AlertNotFound(val alertId: Long) : ResolveAlertError()
}
