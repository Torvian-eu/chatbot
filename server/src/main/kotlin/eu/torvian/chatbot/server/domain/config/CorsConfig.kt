package eu.torvian.chatbot.server.domain.config

/**
 * Domain-level CORS configuration for browser-based API access.
 *
 * @property allowedOrigins Explicit allowlist of origins that may read API responses.
 */
data class CorsConfig(
    val allowedOrigins: List<CorsAllowedOrigin>
)

/**
 * A single allowed browser origin for CORS.
 *
 * @property scheme URI scheme (http or https).
 * @property host Hostname or IP address.
 * @property port Optional explicit port.
 */
data class CorsAllowedOrigin(
    val scheme: String,
    val host: String,
    val port: Int?
) {
    val hostWithOptionalPort: String
        get() {
            val normalizedHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
            return if (port != null) "$normalizedHost:$port" else normalizedHost
        }
}


