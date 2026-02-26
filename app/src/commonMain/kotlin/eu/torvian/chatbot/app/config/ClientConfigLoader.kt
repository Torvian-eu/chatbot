package eu.torvian.chatbot.app.config

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.utils.misc.KmpLogger
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Multiplatform loader responsible for locating, reading, merging, and bootstrapping
 * configuration files for the client application.
 *
 * This loader implements a layered precedence strategy using JSON files:
 * 1. **Configuration Loading**: Loads `config.json`, `secrets.json`, `setup.json` from the physical `config/` directory.
 * 2. **Bootstrap**: Copies `default_config.json`, `default_setup.json` from internal resources
 *    to the physical `config/` directory on first run.
 */
object ClientConfigLoader {

    private val logger: KmpLogger = kmpLogger<ClientConfigLoader>()

    /**
     * JSON instance used for (de)serialization of DTOs.
     *
     * - `allowComments`: Supports C/Java style comments (`//`) in JSON files.
     * - `ignoreUnknownKeys`: Ensures forward-compatibility; allows "documentation keys" (e.g., "//serverUrl").
     * - `prettyPrint`: Generates readable JSON when saving to disk.
     * - `encodeDefaults`: Ensures all fields are written, even if they have default values.
     * - `explicitNulls`: Disabled to keep saved files clean by omitting null fields.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Loads configuration layers from the physical disk.
     *
     * This loads `config.json`, `secrets.json`, and `setup.json` from the specified
     * `configDir`, applying precedence (later layers override earlier ones).
     *
     * @param configDir The multiplatform [Path] to the directory containing the configuration files.
     * @return [Either] containing a [ConfigError] on failure (e.g., file not found or malformed),
     *         or a fully merged [AppConfigDto] on success.
     */
    fun loadConfig(configDir: Path): Either<ConfigError, AppConfigDto> = either {
        logger.info("Loading configuration from disk: $configDir")

        fun loadLayer(name: String, optional: Boolean): AppConfigDto {
            val filePath = Path(configDir, name)
            if (!SystemFileSystem.exists(filePath)) {
                ensure(optional) { ConfigError.FileError.NotFound(filePath.toString()) }
                logger.debug("Optional config file not found: $filePath. Skipping.")
                return AppConfigDto()
            }
            logger.debug("Reading config layer: $filePath")
            return decodeDto(filePath.toString(), filePath.readText())
        }

        // Layer precedence: config.json < secrets.json < setup.json (highest)
        loadLayer("config.json", optional = false)
            .merge(loadLayer("secrets.json", optional = true))
            .merge(loadLayer("setup.json", optional = true))
    }

    /**
     * Bootstraps missing configuration files by copying 'default_*.json' templates
     * from internal `composeResources` to the physical `configDir` on the disk.
     *
     * This is typically run during the initial startup in production mode to provide
     * the user with editable config files.
     *
     * @param configDir The multiplatform [Path] to the target directory for config files.
     * @throws [kotlinx.io.IOException] if directories cannot be created or files cannot be written.
     */
    suspend fun bootstrapFiles(configDir: Path) {
        logger.info("Checking for missing config files in $configDir for bootstrapping.")
        if (!SystemFileSystem.exists(configDir)) {
            SystemFileSystem.createDirectories(configDir)
            logger.info("Created config directory: $configDir")
        }

        val blueprints = mapOf(
            "config.json" to "files/default_config.json",
            "setup.json" to "files/default_setup.json"
        )

        blueprints.forEach { (targetName, resPath) ->
            val targetPath = Path(configDir, targetName)
            if (!SystemFileSystem.exists(targetPath)) {
                logger.info("Bootstrapping missing file: $targetName from internal resource: $resPath")
                targetPath.writeBytes(Res.readBytes(resPath))
            }
        }
    }

    /**
     * Decodes a raw JSON string into an [AppConfigDto].
     *
     * @param label Descriptive label for error reporting (e.g., file path or resource name).
     * @param content The raw JSON text to decode.
     * @return A decoded [AppConfigDto].
     * @raises [ConfigError.FileError.Malformed] if JSON syntax is invalid.
     */
    private fun Raise<ConfigError.FileError>.decodeDto(label: String, content: String): AppConfigDto {
        return try {
            json.decodeFromString<AppConfigDto>(content)
        } catch (e: Exception) {
            logger.error("Error decoding DTO from $label: ${e.message}", e)
            raise(ConfigError.FileError.Malformed(label, e.message ?: "Invalid JSON syntax"))
        }
    }

    /**
     * Persists a partial or full [AppConfigDto] to a specified file within the `configDir`.
     *
     * This is typically used during the setup phase to save generated secrets or updated setup flags.
     * The `json` encoder ensures pretty-printing for human readability.
     *
     * @param dto The configuration data to save.
     * @param configDir The multiplatform [Path] to the configuration directory.
     * @param fileName Target filename within the config directory (e.g., "secrets.json").
     * @throws [kotlinx.io.IOException] if directories cannot be created or the file cannot be written.
     */
    fun saveConfig(dto: AppConfigDto, configDir: Path, fileName: String) {
        val filePath = Path(configDir, fileName)
        filePath.parent?.let {
            if (!SystemFileSystem.exists(it)) {
                SystemFileSystem.createDirectories(it)
                logger.info("Created parent directory for $filePath.")
            }
        }
        logger.info("Saving configuration to: $filePath (file: $fileName)")
        filePath.writeText(json.encodeToString(dto))
    }
}

/**
 * Extension helper for [Path] to read its content as a [String].
 * @return The text content of the file.
 * @throws [kotlinx.io.IOException] if the file cannot be read.
 */
private fun Path.readText(): String {
    return SystemFileSystem.source(this).buffered().use { it.readString() }
}

/**
 * Extension helper for [Path] to write a [String] to the file.
 * @param text The string content to write.
 * @throws [kotlinx.io.IOException] if the file cannot be written.
 */
private fun Path.writeText(text: String) {
    SystemFileSystem.sink(this).buffered().use { it.writeString(text) }
}

/**
 * Extension helper for [Path] to write a [ByteArray] to the file.
 * @param bytes The byte array content to write.
 * @throws [kotlinx.io.IOException] if the file cannot be written.
 */
private fun Path.writeBytes(bytes: ByteArray) {
    SystemFileSystem.sink(this).buffered().use { it.write(bytes) }
}