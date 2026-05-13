package eu.torvian.chatbot.server.service.email.error

/**
 * Error types for mail service operations.
 *
 * These represent logical errors that can occur when sending emails,
 * not technical implementation details.
 */
sealed interface MailError {
    /**
     * A network-level error occurred while sending the email.
     *
     * This could be a connection timeout, DNS resolution failure, or other
     * network infrastructure issues.
     *
     * @property message A human-readable description of the network error.
     */
    data class NetworkError(val message: String) : MailError

    /**
     * Authentication with the mail server failed.
     *
     * This typically indicates invalid credentials or authentication method.
     */
    data object AuthenticationFailed : MailError

    /**
     * Required configuration is missing.
     *
     * This indicates that a required property (e.g., SMTP host, port) was not
     * provided in the configuration.
     *
     * @property missingKey The name of the missing configuration key.
     */
    data class ConfigurationMissing(val missingKey: String) : MailError

    /**
     * The mail server rejected the message.
     *
     * This indicates that the server was reachable and authenticated, but refused
     * to send the email. Common causes include invalid recipient addresses,
     * message content violations, or server policy rejections.
     *
     * @property reason A human-readable description of why the message was rejected.
     */
    data class Rejected(val reason: String) : MailError
}
