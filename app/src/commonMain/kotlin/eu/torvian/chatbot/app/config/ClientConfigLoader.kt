package eu.torvian.chatbot.app.config

import arrow.core.Either

/**
 * Multiplatform interface responsible for locating, reading, merging, and bootstrapping
 * configuration files for the client application.
 *
 * This interface defines a layered precedence strategy using JSON files:
 * 1. **Configuration Loading**: Loads `config.json`, `secrets.json`, `setup.json` from the config directory.
 * 2. **Bootstrap**: Copies `default_config.json`, `default_setup.json` from internal resources
 *    to the config directory on first run.
 * 3. **Saving**: Persists partial or full [AppConfigDto] to a named file within the config directory.
 *
 * Platform-specific implementations handle the actual file I/O.
 */
interface ClientConfigLoader {

    /**
     * Loads configuration layers from the platform-specific storage.
     *
     * Loads `config.json`, `secrets.json`, and `setup.json` from the specified
     * [configDir], applying precedence (later layers override earlier ones).
     *
     * @param configDir The path to the directory containing the configuration files.
     * @return [Either] containing a [ConfigError] on failure (e.g., file not found or malformed),
     *         or a fully merged [AppConfigDto] on success.
     */
    fun loadConfig(configDir: String): Either<ConfigError, AppConfigDto>

    /**
     * Bootstraps missing configuration files by copying 'default_*.json' templates
     * from internal resources to the [configDir].
     *
     * This is typically run during the initial startup to provide
     * the user with editable config files.
     *
     * @param configDir The path to the target directory for config files.
     */
    suspend fun bootstrapFiles(configDir: String)

    /**
     * Persists a partial or full [AppConfigDto] to a specified file within the [configDir].
     *
     * @param dto The configuration data to save.
     * @param configDir The path to the configuration directory.
     * @param fileName Target filename within the config directory (e.g., "secrets.json").
     */
    fun saveConfig(dto: AppConfigDto, configDir: String, fileName: String)
}

