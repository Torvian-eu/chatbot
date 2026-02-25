package eu.torvian.chatbot.server.domain.config

/**
 * Defines the type of network connectors the server should start.
 *
 * This enum controls which HTTP/HTTPS connectors are enabled:
 * - HTTP: Only HTTP connector (not recommended for production)
 * - HTTPS: Only HTTPS connector (recommended for production)
 * - HTTP_AND_HTTPS: Both HTTP and HTTPS connectors
 */
enum class ServerConnectorType {
    /** HTTP connector only (not recommended for production) */
    HTTP,

    /** HTTPS connector only (recommended for production) */
    HTTPS,

    /** Both HTTP and HTTPS connectors */
    HTTP_AND_HTTPS;

    companion object {
        /**
         * Parses a connector type from a string.
         *
         * @param value The string value (case-insensitive)
         * @return The corresponding [ServerConnectorType], or null if invalid
         */
        fun fromString(value: String): ServerConnectorType? {
            return entries.find { it.name.equals(value, ignoreCase = true) }
        }
    }
}

