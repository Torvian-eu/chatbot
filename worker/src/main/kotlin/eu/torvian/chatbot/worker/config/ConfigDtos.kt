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
 * @property workspace Filesystem workspace configuration for built-in tools (nullable).
 * @property builtInTools Built-in tool enablement/prefix configuration (nullable).
 * @property trustedSigners Authorized E2EA signers trusted by this worker (nullable).
 */
@Serializable
data class RuntimeConfigDto(
    val server: ServerConfigDto? = null,
    val identity: IdentityConfigDto? = null,
    val storage: StorageConfigDto? = null,
    val auth: AuthConfigDto? = null,
    val workspace: WorkspaceConfigDto? = null,
    val builtInTools: BuiltInToolsConfigDto? = null,
    val trustedSigners: List<TrustedSignerDto>? = null
)

/**
 * DTO for a signer whose E2EA signatures are trusted by the worker.
 *
 * @property signerId Stable identifier emitted by signed envelopes for the signer.
 * @property publicKeyBase64 Base64-encoded public key used to verify signatures from this signer.
 * @property permissions Permission tokens granted to signatures produced by this signer.
 */
@Serializable
data class TrustedSignerDto(
    val signerId: String,
    val publicKeyBase64: String,
    val permissions: List<String>
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
 * @property displayName Human-readable label for this worker shown in management UIs.
 * @property certificateFingerprint SHA-256 fingerprint of the worker certificate.
 * @property certificatePem PEM-encoded public certificate for the worker identity.
 */
@Serializable
data class IdentityConfigDto(
    val uid: String? = null,
    val displayName: String? = null,
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

/**
 * DTO for the worker's filesystem workspace used by built-in tools.
 *
 * @property path Path to the workspace directory. May be relative to the worker's config directory
 *   or absolute. Relative paths are resolved during configuration assembly.
 */
@Serializable
data class WorkspaceConfigDto(
    val path: String? = null
)

/**
 * DTO for built-in tool configuration.
 *
 * @property enabled List of unprefixed built-in tool names to enable. Use a single `*` to enable all.
 * @property defaultCommandTimeoutSeconds Default timeout in seconds for the `run_command` tool.
 * @property defaultSearchTimeoutSeconds Default timeout in seconds for the `search_text` tool.
 */
@Serializable
data class BuiltInToolsConfigDto(
    val enabled: List<String>? = null,
    val defaultCommandTimeoutSeconds: Long? = null,
    val defaultSearchTimeoutSeconds: Long? = null
)
