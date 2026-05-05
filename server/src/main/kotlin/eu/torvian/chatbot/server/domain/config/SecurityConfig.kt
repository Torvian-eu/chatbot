package eu.torvian.chatbot.server.domain.config

/**
 * Security posture for handling client IP addresses during authentication.
 */
enum class IpSecurityMode {
    /** Standard behavior with no IP-based trust tracking. */
    DISABLED,

    /** Allow new IPs, but keep them unacknowledged until the user confirms them. */
    WARNING,

    /** Block new IPs until the account-owner verification flow is available. */
    STRICT
}

