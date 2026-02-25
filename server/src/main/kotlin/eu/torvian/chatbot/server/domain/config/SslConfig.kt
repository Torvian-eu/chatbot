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
 */
data class SslConfig(
    val port: Int = 8443,
    val keystorePath: String,
    val keystorePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    val generateSelfSigned: Boolean = true
)

