package eu.torvian.chatbot.server.config

import kotlinx.serialization.Serializable

/**
 * A nullable Data Transfer Object (DTO) that mirrors the complete application configuration structure.
 * This DTO is used during the merging process of partial JSON configuration files. All fields are
 * nullable, allowing for incremental building of the configuration from multiple sources, where
 * missing fields simply remain null until a layer provides a value.
 *
 * @property setup Optional [SetupConfigDto] for initial server setup requirements.
 * @property storage Optional [StorageConfigDto] for data storage path configuration.
 * @property network Optional [NetworkConfigDto] for server connectivity settings.
 * @property ssl Optional [SslConfigDto] for SSL/TLS settings.
 * @property database Optional [DatabaseConfigDto] for database connection parameters.
 * @property encryption Optional [EncryptionConfigDto] for data encryption settings.
 * @property jwt Optional [JwtConfigDto] for JSON Web Token authentication settings.
 */
@Serializable
data class AppConfigDto(
    val setup: SetupConfigDto? = null,
    val storage: StorageConfigDto? = null,
    val network: NetworkConfigDto? = null,
    val cors: CorsConfigDto? = null,
    val ssl: SslConfigDto? = null,
    val database: DatabaseConfigDto? = null,
    val encryption: EncryptionConfigDto? = null,
    val jwt: JwtConfigDto? = null
)

/**
 * DTO for CORS configuration.
 *
 * @property allowedOrigins Explicit list of allowed origins in full origin format
 *                          (for example: "https://example.com", "http://localhost:3000").
 */
@Serializable
data class CorsConfigDto(
    val allowedOrigins: List<String>? = null
)

/**
 * DTO for setup configuration, specifically for the 'required' flag.
 *
 * @property required If true, initial server setup steps are needed.
 */
@Serializable
data class SetupConfigDto(val required: Boolean? = null)

/**
 * DTO for storage configuration.
 *
 * Path resolution: baseApplicationPath is derived at runtime from the parent of the config directory.
 * The data directory is always a sibling of the config directory.
 *
 * @property dataDir The subdirectory name within the base application path for runtime data. Typically "data".
 * @property databaseFilename The filename of the database within the data directory. Typically "chatbot.db".
 * @property keystoreFilename The filename of the SSL keystore within the data directory. Typically "keystore.jks".
 * @property logsDir Optional subdirectory name within the base application path for log files.
 */
@Serializable
data class StorageConfigDto(
    val dataDir: String? = null,
    val databaseFilename: String? = null,
    val keystoreFilename: String? = null,
    val logsDir: String? = null
)

/**
 * DTO for network configuration.
 *
 * @property host The host to bind to.
 * @property port The primary port for the HTTP/HTTPS connector.
 * @property path The base URL path for the API.
 * @property connectorType The type of network connectors (e.g., "HTTP", "HTTPS").
 */
@Serializable
data class NetworkConfigDto(
    val host: String? = null,
    val port: Int? = null,
    val path: String? = null,
    val connectorType: String? = null
)

/**
 * DTO for SSL/TLS configuration.
 *
 * Note: keystorePath is intentionally absent — it is resolved from [StorageConfigDto] during
 * DTO→Domain conversion and never read from JSON directly.
 *
 * @property port The HTTPS port.
 * @property keystorePassword Password for the keystore.
 * @property keyAlias Alias for the certificate within the keystore.
 * @property keyPassword Password for the private key.
 * @property generateSelfSigned Flag to enable/disable self-signed certificate generation.
 * @property sanDomains Optional DNS SAN entries for generated self-signed certificates.
 * @property sanIpAddresses Optional IP SAN entries for generated self-signed certificates.
 */
@Serializable
data class SslConfigDto(
    val port: Int? = null,
    val keystorePassword: String? = null,
    val keyAlias: String? = null,
    val keyPassword: String? = null,
    val generateSelfSigned: Boolean? = null,
    val sanDomains: List<String>? = null,
    val sanIpAddresses: List<String>? = null
)

/**
 * DTO for database configuration.
 *
 * Note: filepath is intentionally absent — it is resolved from [StorageConfigDto] during
 * DTO→Domain conversion and never read from JSON directly.
 *
 * @property vendor Database vendor (e.g., "sqlite").
 * @property type Database type (e.g., "file", "memory").
 * @property user Optional database username.
 * @property password Optional database password.
 */
@Serializable
data class DatabaseConfigDto(
    val vendor: String? = null,
    val type: String? = null,
    val user: String? = null,
    val password: String? = null
)

/**
 * DTO for encryption configuration.
 *
 * @property keyVersion The active master key version.
 * @property masterKeys A map of master keys by their version.
 * @property algorithm Optional encryption algorithm override.
 * @property transformation Optional transformation override.
 * @property keySizeBits Optional key size in bits override.
 */
@Serializable
data class EncryptionConfigDto(
    val keyVersion: Int? = null,
    val masterKeys: Map<Int, String>? = null,
    val algorithm: String? = null,
    val transformation: String? = null,
    val keySizeBits: Int? = null
)

/**
 * DTO for JWT (JSON Web Token) configuration.
 *
 * @property issuer JWT issuer claim.
 * @property audience JWT audience claim.
 * @property realm Authentication realm.
 * @property secret JWT signing secret.
 * @property tokenExpirationMs Expiration time for access tokens in milliseconds.
 * @property refreshExpirationMs Expiration time for refresh tokens in milliseconds.
 */
@Serializable
data class JwtConfigDto(
    val issuer: String? = null,
    val audience: String? = null,
    val realm: String? = null,
    val secret: String? = null,
    val tokenExpirationMs: Long? = null,
    val refreshExpirationMs: Long? = null
)