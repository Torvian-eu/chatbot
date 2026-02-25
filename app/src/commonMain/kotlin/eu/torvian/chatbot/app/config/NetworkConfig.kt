package eu.torvian.chatbot.app.config

/**
 * Domain-level configuration for network connectivity.
 *
 * @property serverUrl The base URL of the Chatbot server API (e.g., "https://localhost:8443").
 */
data class NetworkConfig(
    val serverUrl: String
)