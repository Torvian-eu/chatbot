package eu.torvian.chatbot.app.config

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import eu.torvian.chatbot.common.security.EncryptionConfig

/**
 * Deep-merges two [AppConfigDto] instances. Values from [other] take precedence
 * over values from the receiver. Null fields in [other] do not override non-null
 * fields in the receiver.
 *
 * This function handles nested DTOs by merging their fields individually.
 *
 * @param other The [AppConfigDto] to overlay on top of the receiver (may be null).
 * @return A new [AppConfigDto] containing the merged values.
 */
fun AppConfigDto.merge(other: AppConfigDto?): AppConfigDto = AppConfigDto(
    setup = SetupConfigDto(other?.setup?.required ?: setup?.required),
    network = NetworkConfigDto(other?.network?.serverUrl ?: network?.serverUrl),
    storage = StorageConfigDto(
        dataDir = other?.storage?.dataDir ?: storage?.dataDir,
        tokenStorageDir = other?.storage?.tokenStorageDir ?: storage?.tokenStorageDir,
        certificateStorageDir = other?.storage?.certificateStorageDir ?: storage?.certificateStorageDir
    ),
    encryption = EncryptionConfigDto(
        keyVersion = other?.encryption?.keyVersion ?: encryption?.keyVersion,
        masterKeys = (encryption?.masterKeys ?: emptyMap()) + (other?.encryption?.masterKeys ?: emptyMap())
    )
)

/**
 * Converts a merged [AppConfigDto] into a strict domain-level [AppConfiguration] object.
 *
 * This function performs comprehensive validation. If any required fields are missing
 * or invalid, it raises a [ConfigError.ValidationError].
 *
 * Relative storage paths are resolved against [baseDir], ensuring that config and data
 * directories are siblings under the same parent directory.
 *
 * @param baseDir The base directory (parent of config directory), used to resolve relative storage paths.
 * @return [Either] containing a [ConfigError.ValidationError] on failure, or a valid
 *         [AppConfiguration] object on success.
 */
fun AppConfigDto.toDomain(baseDir: String): Either<ConfigError.ValidationError, AppConfiguration> = either {
    // Top-level requirements
    val setupRequired = required("setup.required", setup?.required)

    // Parse and validate nested configuration sections
    val networkConfig = parseNetwork(network)
    val storageConfig = parseStorage(storage, baseDir)
    val encryptionConfig = parseEncryption(encryption)

    AppConfiguration(
        setupRequired = setupRequired,
        network = networkConfig,
        storage = storageConfig,
        encryption = encryptionConfig
    )
}

/**
 * Parses and validates [NetworkConfigDto] into a [NetworkConfig] domain object.
 *
 * @param dto The [NetworkConfigDto] to parse.
 * @return A validated [NetworkConfig].
 * @throws [ConfigError.ValidationError] if any required fields are missing or invalid.
 */
private fun Raise<ConfigError.ValidationError>.parseNetwork(dto: NetworkConfigDto?): NetworkConfig {
    val d = dto ?: raise(ConfigError.ValidationError.MissingKey("network"))
    return NetworkConfig(
        serverUrl = required("network.serverUrl", d.serverUrl)
    )
}

/**
 * Parses and validates [StorageConfigDto] into a [StorageConfig] domain object.
 *
 * The [baseDir] (parent of config directory) is used as the baseApplicationPath.
 * The dataDir field from the DTO is used directly as a simple directory name.
 *
 * @param dto The [StorageConfigDto] to parse.
 * @param baseDir The base directory (parent of config directory).
 * @return A validated [StorageConfig].
 * @throws [ConfigError.ValidationError] if any required fields are missing or invalid.
 */
private fun Raise<ConfigError.ValidationError>.parseStorage(
    dto: StorageConfigDto?,
    baseDir: String
): StorageConfig {
    val d = dto ?: raise(ConfigError.ValidationError.MissingKey("storage"))

    // dataDir is required and used as-is (should be a simple directory name like "data")
    val dataDir = required("storage.dataDir", d.dataDir)

    return StorageConfig(
        baseApplicationPath = baseDir,
        dataDir = dataDir,
        tokenStorageDir = d.tokenStorageDir ?: "tokens",
        certificateStorageDir = d.certificateStorageDir ?: "certs"
    )
}

/**
 * Parses and validates [EncryptionConfigDto] into an [EncryptionConfig] domain object.
 *
 * @param dto The [EncryptionConfigDto] to parse.
 * @return A validated [EncryptionConfig].
 * @throws [ConfigError.ValidationError] if any required fields are missing or invalid.
 */
private fun Raise<ConfigError.ValidationError>.parseEncryption(dto: EncryptionConfigDto?): EncryptionConfig {
    val d = dto ?: raise(ConfigError.ValidationError.MissingKey("encryption"))
    return EncryptionConfig(
        masterKeys = required("encryption.masterKeys", d.masterKeys),
        keyVersion = required("encryption.keyVersion", d.keyVersion)
    )
}


/**
 * Helper function used within an Arrow [Raise] context to assert that a value is not null.
 *
 * If the [value] is `null`, it raises a [ConfigError.ValidationError.MissingKey].
 *
 * @param path The dotted configuration path (e.g., "network.serverUrl") for error reporting.
 * @param value The value to be asserted as non-null.
 * @return The non-null value of type [T].
 * @throws ConfigError.ValidationError.MissingKey if [value] is `null`.
 */
private fun <T> Raise<ConfigError.ValidationError>.required(path: String, value: T?): T =
    value ?: raise(ConfigError.ValidationError.MissingKey(path))