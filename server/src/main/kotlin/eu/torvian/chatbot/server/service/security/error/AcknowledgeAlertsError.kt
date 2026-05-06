package eu.torvian.chatbot.server.service.security.error

/**
 * Error types for security alert acknowledgement operations.
 */
sealed class AcknowledgeAlertsError {
    /**
     * Raised when a restricted session attempts to acknowledge security alerts.
     *
     * @property reason Human-readable description of why the action was denied
     */
    data class InsufficientPermissions(val reason: String = "Action requires a trusted session") : AcknowledgeAlertsError()
}
