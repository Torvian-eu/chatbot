package eu.torvian.chatbot.app.config

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.utils.misc.KmpLogger
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.browser.localStorage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.w3c.dom.Storage

/**
 * Web Storage backed implementation of [ClientConfigLoader] for the WasmJS platform.
 *
 * Since WasmJS has no file system, `configDir` is used as a key namespace prefix in
 * [localStorage]. Each logical config file maps to a single storage entry, e.g.:
 * - `"eu.torvian.chatbot/config.json"`
 * - `"eu.torvian.chatbot/secrets.json"`
 * - `"eu.torvian.chatbot/setup.json"`
 *
 * Default config templates are bootstrapped from bundled Compose Multiplatform resources
 * on first run, just like the file-system implementation.
 */
class WebStorageClientConfigLoader(
    private val storage: Storage = localStorage
) : ClientConfigLoader {

    private val logger: KmpLogger = kmpLogger<WebStorageClientConfigLoader>()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun loadConfig(configDir: String): Either<ConfigError, AppConfigDto> = either {
        logger.info("Loading configuration from localStorage namespace: $configDir")

        fun loadLayer(name: String, optional: Boolean): AppConfigDto {
            val key = storageKey(configDir, name)
            val content = storage.getItem(key)
            if (content == null) {
                ensure(optional) {
                    ConfigError.FileError.NotFound(key)
                }
                logger.debug("Optional config entry not found in localStorage: $key. Skipping.")
                return AppConfigDto()
            }
            logger.debug("Reading config layer from localStorage: $key")
            return decodeDto(key, content)
        }

        // Layer precedence: config.json < secrets.json < setup.json (highest)
        loadLayer("config.json", optional = false)
            .merge(loadLayer("secrets.json", optional = true))
            .merge(loadLayer("setup.json", optional = true))
    }

    override suspend fun bootstrapFiles(configDir: String) {
        logger.info("Bootstrapping missing config entries in localStorage namespace: $configDir")

        val blueprints = mapOf(
            "config.json" to "files/default_config.json",
            "setup.json" to "files/default_setup.json"
        )

        blueprints.forEach { (name, resPath) ->
            val key = storageKey(configDir, name)
            if (storage.getItem(key) == null) {
                logger.info("Bootstrapping missing entry: $key from internal resource: $resPath")
                val content = Res.readBytes(resPath).decodeToString()
                storage.setItem(key, content)
            }
        }
    }

    override fun saveConfig(dto: AppConfigDto, configDir: String, fileName: String) {
        val key = storageKey(configDir, fileName)
        logger.info("Saving configuration to localStorage: $key")
        storage.setItem(key, json.encodeToString(dto))
    }

    private fun storageKey(configDir: String, fileName: String): String = "$configDir/$fileName"

    private fun Raise<ConfigError.FileError>.decodeDto(key: String, content: String): AppConfigDto {
        return try {
            json.decodeFromString<AppConfigDto>(content)
        } catch (e: Exception) {
            logger.error("Error decoding DTO from localStorage key '$key': ${e.message}", e)
            raise(ConfigError.FileError.Malformed(key, e.message ?: "Invalid JSON syntax"))
        }
    }
}
