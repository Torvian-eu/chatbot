package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.server.domain.config.SslConfig

/**
 * Configuration for the server.
 *
 * @property host The host to bind to, e.g., "localhost" or "0.0.0.0".
 * @property port The port for the standard HTTP connector. This is used when SSL is disabled, or when `allowHttpAndHttps` is `true`.
 * @property path The base path for the server.
 * @property sslConfig SSL/TLS configuration. If null, SSL is disabled.
 * @property allowHttpAndHttps If true and sslConfig is enabled, both HTTP and HTTPS connectors will be started.
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val path: String,
    val sslConfig: SslConfig? = null,
    val allowHttpAndHttps: Boolean = false
)
