package eu.torvian.chatbot.app.viewmodel.setup

import eu.torvian.chatbot.app.config.AppConfigDto
import eu.torvian.chatbot.app.config.generateSecureKey
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = createKmpLogger("SetupViewModel")

/**
 * ViewModel for the setup screen.
 *
 * Responsibilities:
 * - Hold and manage setup form state
 * - Handle user events (field changes, button clicks)
 * - Orchestrate key generation on initialization
 * - Call CompleteSetupUseCase to save configuration
 * - Expose StateFlow for UI observation
 *
 * Note: This ViewModel is created manually (not via Koin) because
 * Koin is not initialized until after setup is complete.
 *
 * @property configDir The directory where config files will be saved.
 * @property initialDto Pre-populated configuration data from existing files.
 * @property completeSetupUseCase Use case for completing setup.
 */
class SetupViewModel(
    private val configDir: String,
    private val initialDto: AppConfigDto,
    private val completeSetupUseCase: CompleteSetupUseCase
) {
    // ViewModel scope for coroutines
    // Using Dispatchers.Main for state updates
    private val viewModelScope = CoroutineScope(Dispatchers.Main)

    // Internal mutable state
    private val _state = MutableStateFlow(
        SetupState(
            serverUrl = initialDto.network?.serverUrl ?: "https://localhost:8443",
            dataDir = initialDto.storage?.dataDir ?: "data"
        )
    )

    // Public read-only state
    val state: StateFlow<SetupState> = _state.asStateFlow()

    init {
        // Generate encryption key on initialization
        viewModelScope.launch {
            generateKey()
        }
    }

    /**
     * Handle user events from the UI.
     *
     * This is the single entry point for all user interactions.
     */
    fun onEvent(event: SetupEvent) {
        when (event) {
            is SetupEvent.ServerUrlChanged -> updateServerUrl(event.url)
            is SetupEvent.DataDirChanged -> updateDataDir(event.dir)
            is SetupEvent.ToggleKeyVisibility -> toggleKeyVisibility()
            is SetupEvent.CompleteSetup -> completeSetup()
            is SetupEvent.DismissError -> dismissError()
        }
    }

    /**
     * Generate a secure encryption key.
     */
    private fun generateKey() {
        logger.info("Generating encryption key")
        generateSecureKey().fold(
            ifLeft = { error ->
                logger.error("Failed to generate encryption key: ${error.message}")
                _state.update { it.copy(
                    errorMessage = "Failed to generate encryption key: ${error.message}"
                )}
            },
            ifRight = { key ->
                logger.info("Encryption key generated successfully")
                _state.update { it.copy(encryptionKey = key) }
            }
        )
    }

    /**
     * Update the server URL field.
     */
    private fun updateServerUrl(url: String) {
        _state.update { it.copy(serverUrl = url) }
    }

    /**
     * Update the data directory field.
     */
    private fun updateDataDir(dir: String) {
        _state.update { it.copy(dataDir = dir) }
    }

    /**
     * Toggle encryption key visibility.
     */
    private fun toggleKeyVisibility() {
        _state.update { it.copy(keyVisible = !it.keyVisible) }
    }

    /**
     * Dismiss the error message.
     */
    private fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Complete the setup process.
     *
     * This validates the form, saves configuration files,
     * and updates the state to indicate completion.
     */
    private fun completeSetup() {
        val currentState = _state.value

        // Validate form is ready
        if (!currentState.isValid) {
            _state.update { it.copy(
                errorMessage = "Please fill in all fields"
            )}
            return
        }

        // Start loading
        _state.update { it.copy(
            isLoading = true,
            errorMessage = null
        )}

        viewModelScope.launch {
            logger.info("Starting setup completion")

            // Call use case to complete setup
            completeSetupUseCase(
                configDir = configDir,
                serverUrl = currentState.serverUrl,
                dataDir = currentState.dataDir,
                encryptionKey = currentState.encryptionKey
            ).fold(
                ifLeft = { error ->
                    // Handle error
                    logger.error("Setup failed: ${error.toMessage()}")
                    _state.update { it.copy(
                        isLoading = false,
                        errorMessage = error.toMessage()
                    )}
                },
                ifRight = { appConfig ->
                    // Success - mark as complete
                    logger.info("Setup completed successfully")
                    _state.update { it.copy(
                        isLoading = false,
                        isComplete = true,
                        completedConfig = appConfig
                    )}
                }
            )
        }
    }
}

