package eu.torvian.chatbot.worker.config

import arrow.core.Either
import java.nio.file.Path

/**
 * Contract for loading and saving layered worker configuration.
 */
interface WorkerConfigLoader {
    /**
     * Resolves the worker config directory path based on CLI/env/property/default precedence.
     *
     * @param configDirOverride Optional config directory path provided from CLI parsing.
     * @param envProvider Environment lookup abstraction for testability.
     * @param propertyProvider System property lookup abstraction for testability.
     * @return Resolved config directory path to load.
     */
    fun resolveConfigDir(
        configDirOverride: String? = null,
        envProvider: (String) -> String? = { key -> System.getenv(key) },
        propertyProvider: (String) -> String? = { key -> System.getProperty(key) }
    ): Path

    /**
     * Resolves a concrete config layer file path under the worker config directory.
     *
     * @param configDir Worker config directory.
     * @param fileName Layer file name (for example `application.json`).
     * @return Normalized absolute path to the requested layer file.
     */
    fun resolveLayerPath(configDir: Path, fileName: String): Path

    /**
     * Loads merged config layers into a nullable root DTO.
     *
     * This function combines all configuration layers (base, setup, and environment)
     * into a single DTO object without performing domain-level validation. The resulting
     * DTO may have null or missing required fields, which should be validated separately
     * using the [toDomain] function.
     *
     * @param configDir Worker config directory containing the layered JSON files.
     * @param envProvider Environment lookup abstraction used while resolving `env-mapping.json`.
     * @return Either a logical configuration error or the deserialized root DTO.
     */
    fun loadAppConfigDto(
        configDir: Path,
        envProvider: (String) -> String? = { key -> System.getenv(key) }
    ): Either<WorkerConfigError, AppConfigDto>

    /**
     * Saves a root DTO into a specific config layer file.
     *
     * Atomically writes the serialized DTO to the specified layer file, creating
     * the config directory if necessary. Uses atomic file operations when possible
     * to ensure consistency.
     *
     * @param configDir Worker config directory.
     * @param fileName Layer file name to write (for example `setup.json`).
     * @param dto The DTO object to serialize and save.
     * @return Either a logical configuration error or Unit on success.
     */
    fun saveLayerDto(
        configDir: Path,
        fileName: String,
        dto: AppConfigDto
    ): Either<WorkerConfigError, Unit>
}

