package eu.torvian.chatbot.app.viewmodel.startup

import eu.torvian.chatbot.app.config.AppConfiguration
import eu.torvian.chatbot.app.config.AppConfigDto
import kotlinx.io.files.Path

/**
 * Sealed interface representing all possible application startup states.
 *
 * The state machine transitions:
 * Loading → NeedsSetup → Ready (via setup completion)
 * Loading → Ready (if config already exists)
 * Loading → Error (if fatal error)
 */
sealed interface StartupState {
    /**
     * Initial state while loading configuration.
     */
    data object Loading : StartupState

    /**
     * Setup is required before the app can be used.
     *
     * @property configDir The directory where config files should be saved.
     * @property initialDto Pre-populated configuration data from existing files.
     */
    data class NeedsSetup(
        val configDir: Path,
        val initialDto: AppConfigDto
    ) : StartupState

    /**
     * Configuration is valid and app is ready to use.
     *
     * @property config The validated application configuration.
     */
    data class Ready(val config: AppConfiguration) : StartupState

    /**
     * Fatal error occurred during startup.
     *
     * Note: This state should trigger a user-friendly error dialog,
     * not exitProcess() in the UI layer.
     *
     * @property message Error message to display to user.
     * @property canRetry Whether the user can retry the operation.
     */
    data class Error(
        val message: String,
        val canRetry: Boolean = false
    ) : StartupState
}

