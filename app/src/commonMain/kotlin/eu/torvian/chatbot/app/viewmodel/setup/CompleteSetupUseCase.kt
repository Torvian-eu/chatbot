package eu.torvian.chatbot.app.viewmodel.setup

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.app.config.*
import kotlinx.io.files.Path

/**
 * Use case for completing the initial application setup.
 *
 * This orchestrates the entire setup flow:
 * 1. Create configuration DTO from form data
 * 2. Validate the configuration
 * 3. Save all three config files (config.json, secrets.json, setup.json)
 * 4. Return the validated AppConfiguration
 *
 * Note: Uses ClientConfigLoader directly (no repository abstraction needed).
 */
class CompleteSetupUseCase {
    /**
     * Execute the setup completion flow.
     *
     * @param configDir The directory where config files should be saved.
     * @param serverUrl The server URL from the form.
     * @param dataDir The data directory name from the form.
     * @param encryptionKey The auto-generated encryption key.
     * @return Either Left(SetupError) or Right(validated AppConfiguration).
     */
    operator fun invoke(
        configDir: Path,
        serverUrl: String,
        dataDir: String,
        encryptionKey: String
    ): Either<SetupError, AppConfiguration> = either {
        // 1. Create the configuration DTO
        val configDto = AppConfigDto(
            setup = SetupConfigDto(required = false),
            network = NetworkConfigDto(serverUrl = serverUrl.trim()),
            storage = StorageConfigDto(
                dataDir = dataDir.trim(),
                tokenStorageDir = "tokens",
                certificateStorageDir = "certs"
            ),
            encryption = EncryptionConfigDto(
                keyVersion = 1,
                masterKeys = mapOf(1 to encryptionKey)
            )
        )

        // 2. Validate the configuration
        val baseDir = configDir.parent ?: Path(".")
        val appConfig = configDto.toDomain(baseDir).mapLeft { validationError ->
            SetupError.ValidationError(validationError.toMessage())
        }.bind()

        // 3. Save config.json (network + storage settings)
        try {
            ClientConfigLoader.saveConfig(
                dto = AppConfigDto(
                    network = configDto.network,
                    storage = configDto.storage
                ),
                configDir = configDir,
                fileName = "config.json"
            )
        } catch (e: Exception) {
            raise(SetupError.SaveError("Failed to save config.json: ${e.message}"))
        }

        // 4. Save secrets.json (encryption keys)
        try {
            ClientConfigLoader.saveConfig(
                dto = AppConfigDto(encryption = configDto.encryption),
                configDir = configDir,
                fileName = "secrets.json"
            )
        } catch (e: Exception) {
            raise(SetupError.SaveError("Failed to save secrets.json: ${e.message}"))
        }

        // 5. Save setup.json (mark setup as complete)
        try {
            ClientConfigLoader.saveConfig(
                dto = AppConfigDto(setup = SetupConfigDto(required = false)),
                configDir = configDir,
                fileName = "setup.json"
            )
        } catch (e: Exception) {
            raise(SetupError.SaveError("Failed to save setup.json: ${e.message}"))
        }

        // 6. Return the validated configuration
        appConfig
    }
}

/**
 * Domain errors that can occur during setup.
 */
sealed interface SetupError {
    /**
     * Configuration validation failed.
     */
    data class ValidationError(val message: String) : SetupError

    /**
     * Failed to save configuration files.
     */
    data class SaveError(val message: String) : SetupError

    fun toMessage(): String = when (this) {
        is ValidationError -> message
        is SaveError -> message
    }
}

