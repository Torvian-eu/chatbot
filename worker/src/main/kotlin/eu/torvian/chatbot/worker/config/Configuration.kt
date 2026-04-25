package eu.torvian.chatbot.worker.config

/**
 * Root worker configuration, analogous to the server's root app configuration model.
 *
 * @property setupRequired Whether worker setup flow is still required.
 * @property worker Runtime worker settings used by the process.
 */
data class Configuration(
    val setupRequired: Boolean,
    val worker: RuntimeConfig
)

/**
 * Grouped runtime configuration for the worker process.
 *
 * @property server Server connection settings.
 * @property identity Worker identity and certificate material.
 * @property storage Secrets and token file paths.
 * @property auth Authentication timing configuration.
 */
data class RuntimeConfig(
    val server: ServerConfig,
    val identity: IdentityConfig,
    val storage: StorageConfig,
    val auth: AuthConfig
)

/**
 * Server connection configuration for the worker.
 *
 * @property baseUrl Base URL of the chatbot server.
 */
data class ServerConfig(
    val baseUrl: String
)

/**
 * Worker identity configuration.
 *
 * @property uid Unique identifier for this worker instance.
 * @property certificateFingerprint SHA-256 fingerprint of the worker certificate.
 * @property certificatePem PEM-encoded public certificate.
 */
data class IdentityConfig(
    val uid: String,
    val certificateFingerprint: String,
    val certificatePem: String
)

/**
 * Storage path configuration.
 *
 * @property secretsJsonPath Path to the secrets JSON file containing the private key.
 * @property tokenFilePath Path to the token cache file.
 */
data class StorageConfig(
    val secretsJsonPath: String,
    val tokenFilePath: String
)

/**
 * Authentication timing configuration.
 *
 * @property refreshSkewSeconds Seconds before token expiry to trigger refresh.
 */
data class AuthConfig(
    val refreshSkewSeconds: Long = 60
)
