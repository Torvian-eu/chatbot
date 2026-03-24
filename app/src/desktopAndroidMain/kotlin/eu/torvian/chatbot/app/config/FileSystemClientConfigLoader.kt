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
 * File-system backed implementation of [ClientConfigLoader] for Desktop and Android platforms.
 *
 * Reads and writes configuration files using [kotlinx.io] and bootstraps default files
 * from Compose Multiplatform resources.
 */
class FileSystemClientConfigLoader : ClientConfigLoader {

    private val logger: KmpLogger = kmpLogger<FileSystemClientConfigLoader>()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun loadConfig(configDir: String): Either<ConfigError, AppConfigDto> = either {
        logger.info("Loading configuration from disk: $configDir")
        val configDirPath = Path(configDir)

        fun loadLayer(name: String, optional: Boolean): AppConfigDto {
            val filePath = Path(configDirPath, name)
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

    override suspend fun bootstrapFiles(configDir: String) {
        logger.info("Checking for missing config files in $configDir for bootstrapping.")
        val configDirPath = Path(configDir)
        if (!SystemFileSystem.exists(configDirPath)) {
            SystemFileSystem.createDirectories(configDirPath)
            logger.info("Created config directory: $configDir")
        }

        val blueprints = mapOf(
            "config.json" to "files/config/default_config.json",
            "setup.json" to "files/config/default_setup.json"
        )

        blueprints.forEach { (targetName, resPath) ->
            val targetPath = Path(configDirPath, targetName)
            if (!SystemFileSystem.exists(targetPath)) {
                logger.info("Bootstrapping missing file: $targetName from internal resource: $resPath")
                targetPath.writeBytes(Res.readBytes(resPath))
            }
        }
    }

    override fun saveConfig(dto: AppConfigDto, configDir: String, fileName: String) {
        val configDirPath = Path(configDir)
        val filePath = Path(configDirPath, fileName)
        filePath.parent?.let {
            if (!SystemFileSystem.exists(it)) {
                SystemFileSystem.createDirectories(it)
                logger.info("Created parent directory for $filePath.")
            }
        }
        logger.info("Saving configuration to: $filePath (file: $fileName)")
        filePath.writeText(json.encodeToString(dto))
    }

    private fun Raise<ConfigError.FileError>.decodeDto(label: String, content: String): AppConfigDto {
        return try {
            json.decodeFromString<AppConfigDto>(content)
        } catch (e: Exception) {
            logger.error("Error decoding DTO from $label: ${e.message}", e)
            raise(ConfigError.FileError.Malformed(label, e.message ?: "Invalid JSON syntax"))
        }
    }
}

private fun Path.readText(): String =
    SystemFileSystem.source(this).buffered().use { it.readString() }

private fun Path.writeText(text: String) {
    SystemFileSystem.sink(this).buffered().use { it.writeString(text) }
}

private fun Path.writeBytes(bytes: ByteArray) {
    SystemFileSystem.sink(this).buffered().use { it.write(bytes) }
}

