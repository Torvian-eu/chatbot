package eu.torvian.chatbot.worker.protocol.transport

import eu.torvian.chatbot.common.api.resources.workerWebSocketConnectPath
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolVersion
import java.net.URI

/**
 * Immutable transport settings used by the worker WebSocket session loop.
 *
 * @property workerUid Worker UID used for auth logs and `session.hello` payloads.
 * @property webSocketUrl Absolute WebSocket URL used for the worker protocol connection.
 * @property capabilities Capability names announced during the hello handshake.
 * @property supportedProtocolVersions Protocol versions announced during the hello handshake.
 * @property workerVersion Optional worker build identifier announced during the hello handshake.
 * @property reconnectInitialDelayMs Initial reconnect delay in milliseconds before backoff growth.
 * @property reconnectMaxDelayMs Upper reconnect delay cap in milliseconds.
 * @property reconnectJitterRatio Random jitter ratio applied to reconnect delay in range `[0.0, 1.0]`.
 * @property helloWelcomeTimeoutMs Maximum time allowed for the bounded session-start handshake.
 */
data class WebSocketTransportConfig(
    val workerUid: String,
    val webSocketUrl: String,
    val capabilities: List<String> = emptyList(),
    val supportedProtocolVersions: List<Int> = WorkerProtocolVersion.SUPPORTED.sorted(),
    val workerVersion: String? = null,
    val reconnectInitialDelayMs: Long = 1_000L,
    val reconnectMaxDelayMs: Long = 30_000L,
    val reconnectJitterRatio: Double = 0.2,
    val helloWelcomeTimeoutMs: Long = 10_000L
) {
    companion object {
        /**
         * Builds a [WebSocketTransportConfig] from worker runtime settings.
         *
         * @param serverBaseUrl HTTP(S) server base URL configured for worker auth calls.
         * @param workerUid Worker UID announced in protocol handshakes.
         * @param workerVersion Optional worker version string to announce.
         * @param capabilities Optional capability identifiers to announce.
         * @param webSocketPath Optional worker WebSocket endpoint path. Defaults to the shared
         * canonical worker connect route contract.
         * @return Transport config with a resolved WebSocket URL and protocol defaults.
         */
        fun fromServerBaseUrl(
            serverBaseUrl: String,
            workerUid: String,
            workerVersion: String? = null,
            capabilities: List<String> = emptyList(),
            webSocketPath: String = workerWebSocketConnectPath()
        ): WebSocketTransportConfig {
            return WebSocketTransportConfig(
                workerUid = workerUid,
                webSocketUrl = toWebSocketUrl(serverBaseUrl = serverBaseUrl, webSocketPath = webSocketPath),
                capabilities = capabilities,
                workerVersion = workerVersion
            )
        }

        /**
         * Converts an HTTP(S) base URL plus endpoint path into an absolute WS(S) URL.
         *
         * @param serverBaseUrl Base URL used by worker HTTP calls.
         * @param webSocketPath Worker WebSocket endpoint path.
         * @return Absolute `ws://` or `wss://` URL string.
         */
        private fun toWebSocketUrl(serverBaseUrl: String, webSocketPath: String): String {
            val baseUri = URI(serverBaseUrl)
            val wsScheme = when (baseUri.scheme?.lowercase()) {
                "https" -> "wss"
                "wss" -> "wss"
                else -> "ws"
            }
            val basePath = baseUri.path.orEmpty().trimEnd('/')
            val endpointPath = if (webSocketPath.startsWith('/')) webSocketPath else "/$webSocketPath"
            val mergedPath = "$basePath$endpointPath"

            return URI(
                wsScheme,
                baseUri.userInfo,
                baseUri.host,
                baseUri.port,
                mergedPath,
                null,
                null
            ).toString()
        }
    }
}


