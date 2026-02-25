package eu.torvian.chatbot.app.viewmodel.startup

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.app.config.AppConfiguration
import eu.torvian.chatbot.app.config.AppConfigDto
import eu.torvian.chatbot.app.config.ClientConfigLoader
import eu.torvian.chatbot.app.config.ConfigError
import eu.torvian.chatbot.app.config.toDomain
import kotlinx.io.files.Path

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
class LoadStartupConfigurationUseCase {
    /**
     * Execute the configuration loading flow.
     *
     * @param configDir The directory containing configuration files.
     * @return Either Left(ConfigError) or Right(StartupConfiguration).
     */
    suspend operator fun invoke(
        configDir: Path
    ): Either<ConfigError, StartupConfiguration> = either {
        // 1. Bootstrap default files if they don't exist
        try {
            ClientConfigLoader.bootstrapFiles(configDir)
        } catch (e: Exception) {
            raise(ConfigError.FileError.IOFailure("bootstrap", "Failed to bootstrap defaults: ${e.message}"))
        }

        // 2. Load and merge configuration files
        val configDto = ClientConfigLoader.loadConfig(configDir).bind()

        // 3. Check if setup is required
        if (configDto.setup?.required == true) {
            // Setup is needed - return DTO for pre-population
            StartupConfiguration.NeedsSetup(configDto)
        } else {
            // Setup is complete - validate and convert to domain
            val baseDir = configDir.parent ?: Path(".")
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


