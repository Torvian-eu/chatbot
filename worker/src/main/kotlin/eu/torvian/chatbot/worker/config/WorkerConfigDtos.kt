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
data class WorkerAppConfigDto(
    val setup: WorkerSetupConfigDto? = null,
    val worker: WorkerRuntimeConfigDto? = null
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
data class WorkerSetupConfigDto(
    val required: Boolean? = null
)

/**
 * DTO for worker runtime configuration section.
 *
 * Defines all runtime settings required for the worker to operate, including server
 * connection details, authentication credentials, and other operational parameters.
 * All fields are nullable to support the layered configuration model during merging.
 *
 * @property serverBaseUrl The base URL of the chatbot server the worker connects to.
 * @property workerUid Unique identifier for this worker instance.
 * @property certificateFingerprint The server's certificate fingerprint for TLS verification.
 * @property secretsJsonPath File path to the worker's secrets configuration (e.g., API keys).
 * @property tokenFilePath File path where the worker stores its authentication token.
 * @property refreshSkewSeconds Number of seconds before token expiration to refresh it.
 *                               Defaults to 60 seconds if not specified.
 */
@Serializable
data class WorkerRuntimeConfigDto(
    val serverBaseUrl: String? = null,
    val workerUid: String? = null,
    val certificateFingerprint: String? = null,
    val secretsJsonPath: String? = null,
    val tokenFilePath: String? = null,
    val refreshSkewSeconds: Long? = 60
)

