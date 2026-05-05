package eu.torvian.chatbot.server.domain.config

/**
 * Configuration for reverse proxy (forwarded headers) support.
 *
 * This controls whether the server trusts X-Forwarded-For and Forwarded headers
 * from a reverse proxy. This should be disabled when the server is directly
 * exposed to the internet to prevent IP spoofing attacks.
 *
 * @property enabled Whether forwarded headers should be trusted and processed.
 *                   When false, the server uses the raw connection IP address.
 * @property proxyCount The number of trusted proxies in front of the server.
 *                      Used to skip the correct number of proxies when extracting
 *                      the original client IP from X-Forwarded-For header.
 *                      For example, with Caddy as a single proxy, set to 1.
 * @property useXForwardedHeaders Whether to trust X-Forwarded-For, X-Forwarded-Host,
 *                                 X-Forwarded-Proto, and X-Forwarded-Port headers.
 *                                 Most reverse proxies (Caddy, Nginx, etc.) use these.
 * @property useForwardedHeaders Whether to trust the RFC 7239 Forwarded header.
 *                                This is less common but more standardized.
 *                                Defaults to false for security (prevents header injection
 *                                attacks if the proxy doesn't overwrite this header).
 */
data class ReverseProxyConfig(
    val enabled: Boolean = false,
    val proxyCount: Int = 1,
    val useXForwardedHeaders: Boolean = true,
    val useForwardedHeaders: Boolean = false
)
