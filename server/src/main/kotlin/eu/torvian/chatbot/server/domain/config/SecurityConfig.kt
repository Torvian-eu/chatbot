package eu.torvian.chatbot.server.domain.config

/**
 * Security posture for handling device-based trust during authentication.
 */
enum class AccountSecurityMode {
    /** Standard behavior with no device-based trust tracking. */
    DISABLED,

    /** Allow new devices, but keep them unacknowledged until the user confirms them. */
    WARNING,

    /** Block new devices until the account-owner verification flow is available. */
    STRICT
}
