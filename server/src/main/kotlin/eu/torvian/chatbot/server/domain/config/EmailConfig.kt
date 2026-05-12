package eu.torvian.chatbot.server.domain.config

/**
 * Configuration for email services used by the server.
 *
 * This configuration supports multiple email providers through a flexible properties map,
 * allowing for different authentication methods and transport settings.
 *
 * @property provider The email provider type (e.g., "log" for logging, "smtp" for SMTP).
 * @property fromAddress The default sender address for outgoing emails.
 * @property properties Provider-specific configuration properties (e.g., SMTP host, port, credentials).
 */
data class EmailConfig(
    val provider: String,
    val fromAddress: String,
    val properties: Map<String, String> = emptyMap()
)
