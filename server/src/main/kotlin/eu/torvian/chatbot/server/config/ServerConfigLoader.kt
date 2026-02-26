package eu.torvian.chatbot.server.config

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.recover
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.apache.logging.log4j.LogManager
import java.io.File

/**
 * Responsible for locating, reading, merging, and validating the application's configuration.
 *
 * This loader implements a layered precedence strategy using JSON files:
 * 1. **Base**: `application.json` (The default backbone settings).
 * 2. **Secrets**: `secrets.json` (Passwords and keys, often generated during setup).
 * 3. **Setup**: `setup.json` (Operational flags, e.g., whether setup is still required).
 * 4. **Environment**: `env-mapping.json` (Highest precedence; maps system environment variables to config keys).
 *
 * Configuration directory resolution priority:
 * 1. Environment variable: `CHATBOT_SERVER_CONFIG_DIR`
 * 2. System property: `app.config.dir`
 * 3. Default: `./config`
 *
 * The data directory is always a sibling of the config directory (same parent).
 */
object ServerConfigLoader {

    private const val CONFIG_DIR_ENV_VAR = "CHATBOT_SERVER_CONFIG_DIR"

    private val logger = LogManager.getLogger(ServerConfigLoader::class.java)

    /**
     * JSON instance used for (de)serialization of DTOs and environment mapping.
     *
     * - `allowComments`: Supports C/Java style comments in JSON files.
     * - `ignoreUnknownKeys`: Ensures forward-compatibility if extra keys exist in the JSON.
     * - `prettyPrint`: Generates readable JSON when saving to disk.
     * - `isLenient`: Critical for environment variables; allows string-to-primitive conversion.
     * - `encodeDefaults`: Ensures all fields are written, even if they have default values.
     * - `explicitNulls`: Disabled to keep saved files clean by omitting null fields.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Resolves the base application path (parent of the config directory).
     * The data directory will be a sibling of the config directory under this base path.
     *
     * Examples:
     * - configDir = "config"                       → base = "."
     * - configDir = "/opt/chatbot/config"          → base = "/opt/chatbot"
     * - configDir = "C:\Dev\chatbot-dev\config"    → base = "C:\Dev\chatbot-dev"
     */
    fun resolveBaseApplicationPath(): String {
        val configDir = System.getenv(CONFIG_DIR_ENV_VAR)
            ?: System.getProperty("app.config.dir")
            ?: "config"
        return File(configDir).parentFile?.path ?: "."
    }

    /**
     * Entry point for the configuration lifecycle. Loads and merges all layers into a single [AppConfigDto].
     *
     * Precedence (Highest wins): Environment Mapping > Setup > Secrets > Application Base.
     *
     * Configuration directory resolution priority:
     * 1. Environment variable: CHATBOT_SERVER_CONFIG_DIR
     * 2. System property: app.config.dir
     * 3. Default: ./config
     *
     * @return [Either] containing a [ConfigError] on failure, or a fully merged [AppConfigDto] on success.
     */
    fun loadConfigDto(): Either<ConfigError, AppConfigDto> = either {
        val appConfigDir = System.getenv(CONFIG_DIR_ENV_VAR)
            ?: System.getProperty("app.config.dir")
            ?: "config"

        val source = when {
            System.getenv(CONFIG_DIR_ENV_VAR) != null -> "environment variable $CONFIG_DIR_ENV_VAR"
            System.getProperty("app.config.dir") != null -> "system property 'app.config.dir'"
            else -> "default path"
        }
        logger.info("Configuration Source: Directory from $source: $appConfigDir")
        logger.info("Base application path: ${resolveBaseApplicationPath()}")

        /**
         * Loads a configuration layer from the file system.
         */
        fun loadLayer(name: String, optional: Boolean = false): AppConfigDto = recover({
            val label = "$appConfigDir/$name"
            val raw = loadFileRaw(label)

            // Special handling for the environment mapping layer
            if (name == "env-mapping.json") resolveEnvDto(label, raw)
            else decodeDto(label, raw)
        }) { error ->
            // If the file is missing and it's an optional layer, we just return an empty DTO
            if (optional && error is ConfigError.FileError.NotFound) {
                AppConfigDto()
            } else {
                raise(error)
            }
        }

        // Layer precedence: Environment > Setup > Secrets > Base
        // application.json is NOT optional (the app needs a backbone)
        val baseDto = loadLayer("application.json", optional = false)
        val secretsDto = loadLayer("secrets.json", optional = true)
        val setupDto = loadLayer("setup.json", optional = true)
        val envDto = loadLayer("env-mapping.json", optional = true)

        baseDto
            .merge(secretsDto)
            .merge(setupDto)
            .merge(envDto)
    }

    /**
     * Decodes a raw JSON string into an [AppConfigDto].
     *
     * @param name Descriptive label for error reporting (e.g., file path).
     * @param content The raw JSON text to decode.
     * @return A decoded [AppConfigDto], or an empty DTO if [content] is blank.
     * @raises [ConfigError.FileError.Malformed] if JSON syntax is invalid.
     * @raises [ConfigError.FileError.IOFailure] for unexpected decoding exceptions.
     */
    private fun Raise<ConfigError.FileError>.decodeDto(name: String, content: String): AppConfigDto {
        if (content.isBlank()) return AppConfigDto()
        return try {
            json.decodeFromString<AppConfigDto>(content)
        } catch (e: SerializationException) {
            raise(ConfigError.FileError.Malformed(name, e.message ?: "Invalid JSON syntax"))
        } catch (e: Exception) {
            raise(ConfigError.FileError.IOFailure(name, e.message ?: "Unexpected decoding error"))
        }
    }

    /**
     * Resolves an environment variable mapping layer.
     *
     * Treats the string values in [mappingContent] as environment variable names,
     * looks them up in the system environment, and builds a resolved DTO.
     *
     * @param name Descriptive label for error reporting.
     * @param mappingContent The raw JSON mapping string.
     * @return A resolved [AppConfigDto].
     * @raises [ConfigError.FileError.Malformed] if the mapping structure is invalid.
     */
    private fun Raise<ConfigError.FileError>.resolveEnvDto(name: String, mappingContent: String): AppConfigDto {
        if (mappingContent.isBlank()) return AppConfigDto()
        return try {
            val mappingElement = json.parseToJsonElement(mappingContent) as? JsonObject
                ?: raise(ConfigError.FileError.Malformed(name, "Root of environment mapping must be an object"))

            val resolvedElement = substituteEnv(mappingElement)
            json.decodeFromJsonElement<AppConfigDto>(resolvedElement)
        } catch (e: SerializationException) {
            raise(ConfigError.FileError.Malformed(name, e.message ?: "Malformed mapping JSON"))
        } catch (e: Exception) {
            raise(ConfigError.FileError.IOFailure(name, e.message ?: "Environment resolution failed"))
        }
    }


    /**
     * Recursively traverses a JSON structure, replacing string values with values
     * found in the System Environment.
     *
     * If an environment variable is missing, the key is removed from the resulting
     * object to avoid null-pointer issues during DTO decoding.
     */
    private fun substituteEnv(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            // Filter out entries where the value resolves to JsonNull
            val resolvedMap = element.mapValues { substituteEnv(it.value) }
                .filterValues { it !is JsonNull }
            JsonObject(resolvedMap)
        }

        is JsonArray -> {
            // Filter out nulls from arrays
            val resolvedList = element.map { substituteEnv(it) }
                .filter { it !is JsonNull }
            JsonArray(resolvedList)
        }

        is JsonPrimitive -> {
            if (element.isString) {
                val envValue = System.getenv(element.content)
                // If missing, return JsonNull so the parent can filter it out
                if (envValue != null) JsonPrimitive(envValue) else JsonNull
            } else element
        }
    }

    /**
     * Reads a file from the physical filesystem.
     *
     * @param path The filesystem path to read.
     * @return The text content of the file.
     * @raises [ConfigError.FileError.NotFound] if the file is missing.
     * @raises [ConfigError.FileError.IOFailure] if the file cannot be read.
     */
    private fun Raise<ConfigError.FileError>.loadFileRaw(path: String): String {
        val file = File(path)
        ensure(file.exists()) { ConfigError.FileError.NotFound(path) }
        return try {
            file.readText()
        } catch (e: Exception) {
            raise(ConfigError.FileError.IOFailure(path, e.message ?: "Read operation failed"))
        }
    }


    /**
     * Persists a partial or full [AppConfigDto] to the configuration directory.
     *
     * Uses the same resolution logic as [loadConfigDto]:
     * 1. Environment variable: CHATBOT_SERVER_CONFIG_DIR
     * 2. System property: app.config.dir
     * 3. Default: ./config
     *
     * Typically used to save secrets generated during the setup phase into `secrets.json`.
     *
     * @param dto The configuration data to save.
     * @param fileName Target filename within the config directory (e.g., "secrets.json").
     */
    fun saveConfig(dto: AppConfigDto, fileName: String) {
        val dirPath = System.getenv(CONFIG_DIR_ENV_VAR)
            ?: System.getProperty("app.config.dir")
            ?: "config"
        val file = File(dirPath, fileName)

        logger.info("Saving configuration to: ${file.absolutePath}")
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(dto))
    }
}