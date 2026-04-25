package eu.torvian.chatbot.worker.config

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

/**
 * Loads worker runtime configuration from a layered config directory.
 *
 * The worker uses a server-like configuration structure with these files:
 * - `application.json`: required base config.
 * - `setup.json`: optional setup/runtime overrides.
 * - `env-mapping.json`: optional mapping layer that resolves values from environment variables.
 *
 * Path resolution precedence:
 * 1. Command-line argument `--config=<path-to-config-dir>`
 * 2. Environment variable `CHATBOT_WORKER_CONFIG_DIR`
 * 3. System property `worker.config.dir`
 * 4. Default `./worker-config`
 *
 * Layer precedence (highest wins):
 * 1. `env-mapping.json` resolved from current process environment
 * 2. `setup.json`
 * 3. `application.json`
 *
 * @property json Shared JSON codec used by the config loader.
 */
class DefaultWorkerConfigLoader : WorkerConfigLoader {
    /**
     * Shared JSON codec used by the config loader.
     *
     * It enables comments for template files, lenient parsing for mapping inputs, and
     * `encodeDefaults` so saved config files remain explicit and reproducible.
     */
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        allowComments = true
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun resolveConfigDir(
        configDirOverride: String?,
        envProvider: (String) -> String?,
        propertyProvider: (String) -> String?
    ): Path {
        val envPath = envProvider(CONFIG_DIR_ENV)
        val propertyPath = propertyProvider(CONFIG_DIR_PROPERTY)
        return Path(configDirOverride ?: envPath ?: propertyPath ?: DEFAULT_CONFIG_DIR)
    }


    override fun resolveLayerPath(configDir: Path, fileName: String): Path {
        return configDir.toAbsolutePath().normalize().resolve(fileName)
    }

    override fun loadAppConfigDto(
        configDir: Path,
        envProvider: (String) -> String?
    ): Either<WorkerConfigError, AppConfigDto> = either {
        val mergedConfig = loadMergedConfig(configDir, envProvider).bind()
        try {
            json.decodeFromJsonElement(AppConfigDto.serializer(), mergedConfig)
        } catch (e: SerializationException) {
            raise(WorkerConfigError.ConfigInvalid("Failed to parse worker config: ${e.message}"))
        }
    }

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
     * @return Either a logical configuration error or `Unit` on success.
     */
    override fun saveLayerDto(
        configDir: Path,
        fileName: String,
        dto: AppConfigDto
    ): Either<WorkerConfigError, Unit> = either {
        val normalizedConfigDir = normalizeAndEnsureConfigDir(configDir)

        val path = resolveLayerPath(normalizedConfigDir, fileName)
        writeLayer(path, json.encodeToString(AppConfigDto.serializer(), dto))
    }

    /**
     * Loads and deep-merges all config layers into one JSON object.
     *
     * The merge result preserves unrelated top-level groups, allowing future worker config
     * sections to be added without changing the loader pipeline.
     *
     * Bootstraps missing default config files from bundled resources before loading and merging.
     *
     * @param configDir Worker config directory containing the layered JSON files.
     * @param envProvider Environment lookup abstraction used while resolving `env-mapping.json`.
     * @return Either a logical configuration error or the merged root JSON object.
     */
    private fun loadMergedConfig(
        configDir: Path,
        envProvider: (String) -> String?
    ): Either<WorkerConfigError, JsonObject> = either {
        val normalizedConfigDir = normalizeAndEnsureConfigDir(configDir)

        bootstrapDefaultConfigFiles(normalizedConfigDir)

        val baseLayer = loadLayer(normalizedConfigDir, APPLICATION_FILE_NAME, optional = false)
        val setupLayer = loadLayer(normalizedConfigDir, SETUP_FILE_NAME, optional = true)
        val envLayer = loadEnvLayer(
            normalizedConfigDir,
            ENV_MAPPING_FILE_NAME,
            optional = true,
            envProvider = envProvider
        )

        deepMerge(deepMerge(baseLayer, setupLayer), envLayer)
    }

    /**
     * Bootstraps missing default config files into a fresh worker config directory.
     *
     * Existing files are left untouched so local edits are preserved. Only files listed in
     * [BOOTSTRAP_CONFIG_FILES] are created from bundled resources.
     *
     * @receiver The Raise context used to propagate configuration errors.
     * @param configDir Target worker config directory.
     */
    private fun Raise<WorkerConfigError>.bootstrapDefaultConfigFiles(configDir: Path) {
        BOOTSTRAP_CONFIG_FILES.forEach { fileName ->
            val targetFile = resolveLayerPath(configDir, fileName)
            if (targetFile.exists()) return@forEach

            val resourcePath = "$DEFAULT_CONFIG_RESOURCE_DIR/$fileName"
            val content = loadBundledResource(resourcePath)

            try {
                targetFile.writeText(content)
            } catch (e: Exception) {
                raise(
                    WorkerConfigError.ConfigReadFailed(
                        targetFile.absolutePathString(),
                        e.message ?: "Failed to write default config file"
                    )
                )
            }
        }
    }

    /**
     * Normalizes and validates the worker config directory path.
     *
     * Ensures the target exists (creating it when needed) and is a directory.
     *
     * @receiver The Raise context used to propagate configuration errors.
     * @param configDir Candidate config directory path.
     * @return Normalized absolute config directory path.
     */
    private fun Raise<WorkerConfigError>.normalizeAndEnsureConfigDir(configDir: Path): Path {
        val normalizedConfigDir = configDir.toAbsolutePath().normalize()
        ensure(normalizedConfigDir.exists() || normalizedConfigDir.createDirectories().toFile().exists()) {
            WorkerConfigError.ConfigReadFailed(
                normalizedConfigDir.absolutePathString(),
                "Unable to create configuration directory"
            )
        }
        ensure(normalizedConfigDir.isDirectory()) {
            WorkerConfigError.ConfigReadFailed(
                normalizedConfigDir.absolutePathString(),
                "Configuration path is not a directory"
            )
        }
        return normalizedConfigDir
    }

    /**
     * Reads a bundled default config template from the classpath.
     *
     * Used during bootstrap to initialize missing configuration files from the
     * application's bundled resources (jar/classpath).
     *
     * @receiver The Raise context used to propagate configuration errors.
     * @param resourcePath Classpath resource path to read (e.g., "/default-config/application.json").
     * @return The raw text content of the bundled resource.
     */
    private fun Raise<WorkerConfigError>.loadBundledResource(resourcePath: String): String {
        val stream = WorkerConfigLoader::class.java.getResourceAsStream(resourcePath)
            ?: raise(WorkerConfigError.ConfigMissing("classpath:$resourcePath"))

        return try {
            stream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            raise(
                WorkerConfigError.ConfigReadFailed(
                    "classpath:$resourcePath",
                    e.message ?: "Failed to read default config resource"
                )
            )
        }
    }

    /**
     * Reads a single JSON config layer from disk.
     *
     * When [optional] is `true`, a missing file resolves to an empty object so it can be
     * ignored by the merge pipeline. When [optional] is `false`, a missing file is treated as
     * a logical configuration error.
     *
     * @receiver The Raise context used to propagate configuration errors.
     * @param configDir Worker config directory.
     * @param fileName Layer file name to read.
     * @param optional Whether the layer may be absent without failing the load.
     * @return The parsed JSON object for the layer, or an empty object when the layer is optional and missing.
     */
    private fun Raise<WorkerConfigError>.loadLayer(
        configDir: Path,
        fileName: String,
        optional: Boolean
    ): JsonObject {
        val path = resolveLayerPath(configDir, fileName)
        if (!Files.exists(path)) {
            if (optional) {
                return JsonObject(emptyMap())
            }
            raise(WorkerConfigError.ConfigMissing(path.toString()))
        }

        val raw = try {
            Files.readString(path)
        } catch (e: Exception) {
            raise(WorkerConfigError.ConfigReadFailed(path.toString(), e.message ?: e::class.simpleName.orEmpty()))
        }

        return try {
            json.parseToJsonElement(raw).let { element ->
                element as? JsonObject
                    ?: raise(WorkerConfigError.ConfigInvalid("$path must contain a JSON object"))
            }
        } catch (e: SerializationException) {
            raise(WorkerConfigError.ConfigInvalid("Failed to parse JSON at $path: ${e.message}"))
        }
    }

    /**
     * Resolves the environment-mapping layer into concrete values.
     *
     * Each string value in the mapping file is treated as an environment variable name.
     * Missing variables are removed from the resolved structure so they do not override
     * values from lower-precedence layers.
     *
     * @receiver The Raise context used to propagate configuration errors.
     * @param configDir Worker config directory.
     * @param fileName Environment-mapping file name.
     * @param optional Whether the layer may be absent without failing the load.
     * @param envProvider Environment lookup abstraction.
     * @return A resolved JSON object, or an empty object when nothing is provided.
     */
    private fun Raise<WorkerConfigError>.loadEnvLayer(
        configDir: Path,
        fileName: String,
        optional: Boolean,
        envProvider: (String) -> String?
    ): JsonObject {
        val mappingLayer = loadLayer(configDir, fileName, optional)
        if (mappingLayer.isEmpty()) {
            return JsonObject(emptyMap())
        }

        val resolved = substituteEnv(mappingLayer, envProvider)
        return resolved as? JsonObject ?: JsonObject(emptyMap())
    }

    /**
     * Recursively substitutes environment-variable references in a JSON structure.
     *
     * String primitives are treated as environment variable names. When the variable is not
     * present, the value resolves to `JsonNull` so it can be filtered out by callers.
     * Non-string primitives are returned unchanged.
     *
     * @param element JSON element to transform.
     * @param envProvider Environment lookup abstraction.
     * @return The transformed JSON element, or `JsonNull` when the value should be omitted.
     */
    private fun substituteEnv(element: JsonElement, envProvider: (String) -> String?): JsonElement = when (element) {
        is JsonObject -> {
            val resolved = element.mapValues { substituteEnv(it.value, envProvider) }.filterValues { it !is JsonNull }
            if (resolved.isEmpty()) JsonNull else JsonObject(resolved)
        }

        is JsonArray -> {
            val resolved = element.map { substituteEnv(it, envProvider) }.filter { it !is JsonNull }
            if (resolved.isEmpty()) JsonNull else JsonArray(resolved)
        }

        is JsonPrimitive -> {
            if (element.isString) {
                envProvider(element.content)?.let { JsonPrimitive(it) } ?: JsonNull
            } else {
                element
            }
        }
    }

    /**
     * Deep-merges two JSON objects recursively.
     *
     * Existing base values are preserved unless the overlay provides a replacement.
     * Nested objects are merged recursively so future config groups can be extended
     * without rewriting the merge logic.
     *
     * @param base Lower-precedence JSON object.
     * @param overlay Higher-precedence JSON object.
     * @return A new merged JSON object.
     */
    private fun deepMerge(base: JsonObject, overlay: JsonObject): JsonObject = buildJsonObject {
        base.forEach { (key, value) ->
            if (key !in overlay) {
                put(key, value)
            }
        }

        overlay.forEach { (key, overlayValue) ->
            val baseValue = base[key]
            if (baseValue is JsonObject && overlayValue is JsonObject) {
                put(key, deepMerge(baseValue, overlayValue))
            } else {
                put(key, overlayValue)
            }
        }
    }

    /**
     * Atomically writes a JSON layer file.
     *
     * Uses atomic file operations when available to ensure consistency. Falls back to
     * non-atomic write if atomic operations are not supported by the filesystem.
     *
     * @receiver The Raise context used to propagate configuration errors.
     * @param path File path to write to.
     * @param content Serialized JSON content to write.
     */
    private fun Raise<WorkerConfigError>.writeLayer(path: Path, content: String) {
        var tempFile: Path? = null
        try {
            val parent = path.parent ?: raise(
                WorkerConfigError.ConfigReadFailed(path.toString(), "Unable to resolve parent directory")
            )
            Files.createDirectories(parent)
            tempFile = Files.createTempFile(parent, "${path.fileName}.", ".tmp")
            Files.writeString(tempFile, content)

            try {
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            raise(WorkerConfigError.ConfigReadFailed(path.toString(), e.message ?: e::class.simpleName.orEmpty()))
        } finally {
            if (tempFile != null && Files.exists(tempFile)) {
                runCatching { Files.delete(tempFile) }
            }
        }
    }

    companion object {
        /**
         * Layer files that are bootstrapped into a fresh config directory.
         */
        private val BOOTSTRAP_CONFIG_FILES = listOf(
            APPLICATION_FILE_NAME,
            SETUP_FILE_NAME,
            ENV_MAPPING_FILE_NAME
        )

        /**
         * Base application config file name in the layered worker configuration model.
         */
        const val APPLICATION_FILE_NAME = "application.json"

        /**
         * Optional setup overrides file name in the layered worker configuration model.
         */
        const val SETUP_FILE_NAME = "setup.json"

        /**
         * Optional environment mapping file name in the layered worker configuration model.
         */
        const val ENV_MAPPING_FILE_NAME = "env-mapping.json"

        /**
         * Environment variable that can override the worker config directory.
         */
        private const val CONFIG_DIR_ENV = "CHATBOT_WORKER_CONFIG_DIR"

        /**
         * System property that can override the worker config directory.
         */
        private const val CONFIG_DIR_PROPERTY = "worker.config.dir"

        /**
         * Default config directory used when no explicit override is provided.
         */
        private const val DEFAULT_CONFIG_DIR = "./config"

        /**
         * Classpath root that contains bootstrapped default worker config templates.
         */
        private const val DEFAULT_CONFIG_RESOURCE_DIR = "/default-config"
    }
}






