package eu.torvian.chatbot.app.viewmodel.startup

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.app.config.*
import eu.torvian.chatbot.app.utils.platform.FilePathUtils

/**
 * Use case for loading startup configuration.
 *
 * This orchestrates the application startup flow:
 * 1. Bootstrap default configuration files if missing
 * 2. Load and merge all configuration files
 * 3. Determine if setup is required or if the app is ready
 * 4. Return the appropriate startup state
 *
 * Note: Uses ClientConfigLoader directly (no repository abstraction needed).
 */
class LoadStartupConfigurationUseCase(
    private val configLoader: ClientConfigLoader
) {
    /**
     * Execute the configuration loading flow.
     *
     * @param configDir The directory containing configuration files.
     * @return Either Left(ConfigError) or Right(StartupConfiguration).
     */
    suspend operator fun invoke(
        configDir: String
    ): Either<ConfigError, StartupConfiguration> = either {
        // 1. Bootstrap default files if they don't exist
        try {
            configLoader.bootstrapFiles(configDir)
        } catch (e: Exception) {
            raise(ConfigError.FileError.IOFailure("bootstrap", "Failed to bootstrap defaults: ${e.message}"))
        }

        // 2. Load and merge configuration files
        val configDto = configLoader.loadConfig(configDir).bind()

        // 3. Check if setup is required
        if (configDto.setup?.required == true) {
            // Setup is needed - return DTO for pre-population
            StartupConfiguration.NeedsSetup(configDto)
        } else {
            // Setup is complete - validate and convert to domain
            val baseDir = FilePathUtils.parentPath(configDir)
            val appConfig = configDto.toDomain(baseDir).bind()
            StartupConfiguration.Ready(appConfig)
        }
    }
}

/**
 * Result of loading startup configuration.
 *
 * This represents the two possible outcomes:
 * - App needs setup (show SetupScreen)
 * - App is ready (show main UI)
 */
sealed interface StartupConfiguration {
    /**
     * Setup is required before the app can be used.
     *
     * @property initialDto Pre-populated configuration data for the setup form.
     */
    data class NeedsSetup(val initialDto: AppConfigDto) : StartupConfiguration

    /**
     * App is ready to use with validated configuration.
     *
     * @property config The validated application configuration.
     */
    data class Ready(val config: AppConfiguration) : StartupConfiguration
}


