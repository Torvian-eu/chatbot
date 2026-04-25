package eu.torvian.chatbot.worker.config

import kotlinx.serialization.Serializable

/**
 * Nullable root DTO used while merging layered worker config JSON.
 *
 * This DTO represents the deserialized JSON from merged configuration layers and allows
 * null values for all fields, supporting the merging process where different layers
 * provide different configuration values.
 *
 * @property setup Setup configuration options (nullable).
 * @property worker Runtime worker configuration (nullable).
 */
@Serializable
data class AppConfigDto(
    val setup: SetupConfigDto? = null,
    val worker: RuntimeConfigDto? = null
)

/**
 * DTO for the setup section in worker configuration.
 *
 * Provides optional configuration for the worker setup flow.
 *
 * @property required Whether the worker setup is required before the worker can start.
 *                     Defaults to null (treated as true by domain logic).
 */
@Serializable
data class SetupConfigDto(
    val required: Boolean? = null
)

/**
 * DTO for worker runtime configuration section.
 *
 * Defines all runtime settings required for the worker to operate, grouped by concern.
 * All fields are nullable to support the layered configuration model during merging.
 *
 * @property server Server connection configuration (nullable).
 * @property identity Worker identity and certificate configuration (nullable).
 * @property storage File path configuration for secrets and token storage (nullable).
 * @property auth Authentication timing and skew configuration (nullable).
 */
@Serializable
data class RuntimeConfigDto(
    val server: ServerConfigDto? = null,
    val identity: IdentityConfigDto? = null,
    val storage: StorageConfigDto? = null,
    val auth: AuthConfigDto? = null
)

/**
 * DTO for server connection configuration.
 *
 * @property baseUrl The base URL of the chatbot server the worker connects to.
 */
@Serializable
data class ServerConfigDto(
    val baseUrl: String? = null
)

/**
 * DTO for worker identity configuration.
 *
 * @property uid Unique identifier for this worker instance.
 * @property certificateFingerprint SHA-256 fingerprint of the worker certificate.
 * @property certificatePem PEM-encoded public certificate for the worker identity.
 */
@Serializable
data class IdentityConfigDto(
    val uid: String? = null,
    val certificateFingerprint: String? = null,
    val certificatePem: String? = null
)

/**
 * DTO for storage path configuration.
 *
 * @property secretsJsonPath File path to the worker's secrets file containing the private key.
 * @property tokenFilePath File path where the worker stores its authentication token.
 */
@Serializable
data class StorageConfigDto(
    val secretsJsonPath: String? = null,
    val tokenFilePath: String? = null
)

/**
 * DTO for authentication timing configuration.
 *
 * @property refreshSkewSeconds Number of seconds before token expiration to refresh it.
 *                               Defaults to 60 seconds if not specified.
 */
@Serializable
data class AuthConfigDto(
    val refreshSkewSeconds: Long? = 60
)
