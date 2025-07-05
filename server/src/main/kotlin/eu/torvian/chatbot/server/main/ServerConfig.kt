package eu.torvian.chatbot.server.main

/**
 * Configuration for the server.
 *
 * @property scheme The scheme to use for the server (e.g., "http" or "https").
 * @property host The host to bind to, e.g., "localhost" or "0.0.0.0".
 * @property port The port to bind to. If 0, a random available port will be used.
 * @property path The base path for the server.
 */
data class ServerConfig(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String
)
