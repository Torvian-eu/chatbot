package eu.torvian.chatbot.app.config

import kotlinx.serialization.Serializable

/**
 * Root Data Transfer Object (DTO) for the client application's configuration.
 *
 * This aggregate DTO directly mirrors the [AppConfiguration] domain structure,
 * but with all fields being nullable. This design allows for incremental building
 * of the configuration from multiple JSON sources during the merging process,
 * where missing fields initially remain null until a layer provides a value.
 */
@Serializable
data class AppConfigDto(
    val setup: SetupConfigDto? = null,
    val network: NetworkConfigDto? = null,
    val storage: StorageConfigDto? = null,
    val encryption: EncryptionConfigDto? = null
)

/**
 * DTO for setup-related configuration flags.
 *
 * @property required If `true`, the client application needs to run an initial setup wizard.
 *                    This flag is managed by the application itself.
 */
@Serializable
data class SetupConfigDto(val required: Boolean? = null)

/**
 * DTO for network-related configuration, primarily server connectivity.
 *
 * @property serverUrl The base URL of the Chatbot server API (e.g., "https://localhost:8443").
 */
@Serializable
data class NetworkConfigDto(val serverUrl: String? = null)

/**
 * DTO for client-side storage configurations.
 *
 * @property dataDir The subdirectory within the base application path where user data
 *                   (database, logs, etc.) will be stored. Typically "data".
 * @property tokenStorageDir The subdirectory within [dataDir] for storing authentication tokens.
 * @property certificateStorageDir The subdirectory within [dataDir] for storing certificates.
 */
@Serializable
data class StorageConfigDto(
    val dataDir: String? = null,
    val tokenStorageDir: String? = null,
    val certificateStorageDir: String? = null
)

/**
 * DTO for encryption-related configuration.
 *
 * @property keyVersion The current active version of the master key used for encryption.
 * @property masterKeys A map where keys are master key versions and values are their Base64-encoded strings.
 */
@Serializable
data class EncryptionConfigDto(
    val keyVersion: Int? = null,
    val masterKeys: Map<Int, String>? = null
)