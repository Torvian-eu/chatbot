package eu.torvian.chatbot.app.viewmodel.setup

import eu.torvian.chatbot.app.config.AppConfiguration

/**
 * Immutable state object for the setup screen.
 *
 * This represents the entire state of the setup form at any given moment.
 * Changes to state are made by creating new instances with updated values.
 *
 * @property serverUrl The server URL being configured.
 * @property dataDir The data directory name being configured.
 * @property encryptionKey The auto-generated encryption key.
 * @property keyVisible Whether the encryption key is currently visible.
 * @property errorMessage Error message to display, null if no error.
 * @property isLoading Whether an operation is in progress.
 * @property isComplete Whether setup has completed successfully.
 * @property completedConfig The final validated configuration, only set when isComplete is true.
 */
data class SetupState(
    val serverUrl: String = "https://localhost:8443",
    val dataDir: String = "data",
    val encryptionKey: String = "",
    val keyVisible: Boolean = false,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val isComplete: Boolean = false,
    val completedConfig: AppConfiguration? = null
) {
    /**
     * Whether the form is valid and can be submitted.
     */
    val isValid: Boolean
        get() = encryptionKey.isNotEmpty() &&
                serverUrl.isNotBlank() &&
                dataDir.isNotBlank() &&
                !isLoading
}

