package eu.torvian.chatbot.server.domain.config

/**
 * Configuration for SSL/TLS settings.
 *
 * @property port HTTPS port (default: 8443).
 * @property keystorePath Path to keystore file.
 * @property keystorePassword Keystore password.
 * @property keyAlias Certificate alias in keystore.
 * @property keyPassword Private key password.
 * @property generateSelfSigned Auto-generate self-signed cert for local deployments.
 * @property sanDomains DNS names added to certificate Subject Alternative Names.
 * @property sanIpAddresses IP addresses added to certificate Subject Alternative Names.
 */
data class SslConfig(
    val port: Int = 8443,
    val keystorePath: String,
    val keystorePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    val generateSelfSigned: Boolean = true,
    val sanDomains: List<String> = DEFAULT_SAN_DOMAINS,
    val sanIpAddresses: List<String> = DEFAULT_SAN_IP_ADDRESSES
) {
    companion object {
        val DEFAULT_SAN_DOMAINS: List<String> = listOf("localhost")
        val DEFAULT_SAN_IP_ADDRESSES: List<String> = listOf("127.0.0.1", "0.0.0.0", "10.0.2.2")
    }
}

