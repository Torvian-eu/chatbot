package eu.torvian.chatbot.server.domain.config

/**
 * Technical configuration for the server's network layer.
 *
 * @property host The IP address or hostname to bind to (e.g., "localhost" or "0.0.0.0").
 * @property port The port for the HTTP connector. Used when connectorType is HTTP or HTTP_AND_HTTPS.
 * @property path The base URL path for the API.
 * @property connectorType The type of connectors to start (HTTP, HTTPS, or HTTP_AND_HTTPS).
 */
data class NetworkConfig(
    val host: String,
    val port: Int,
    val path: String,
    val connectorType: ServerConnectorType = ServerConnectorType.HTTP
)