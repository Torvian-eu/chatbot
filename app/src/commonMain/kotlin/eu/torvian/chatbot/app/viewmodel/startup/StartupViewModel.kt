package eu.torvian.chatbot.app.viewmodel.startup

import eu.torvian.chatbot.app.config.AppConfiguration
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val logger = createKmpLogger("StartupViewModel")

/**
 * ViewModel for managing application startup state.
 *
 * Responsibilities:
 * - Manage application startup state machine
 * - Call LoadStartupConfigurationUseCase to load config
 * - Handle configuration loading errors
 * - Transition between states (Loading → NeedsSetup/Ready/Error)
 * - Expose StateFlow for UI observation
 *
 * Note: This ViewModel is created manually (not via Koin) because
 * Koin is not initialized until the Ready state is reached.
 *
 * @property configDir The directory containing configuration files.
 * @property loadConfigUseCase Use case for loading startup configuration.
 */
class StartupViewModel(
    private val configDir: String,
    private val loadConfigUseCase: LoadStartupConfigurationUseCase
) {
    // ViewModel scope for coroutines
    private val viewModelScope = CoroutineScope(Dispatchers.Main)

    // Internal mutable state
    private val _state = MutableStateFlow<StartupState>(StartupState.Loading)

    // Public read-only state
    val state: StateFlow<StartupState> = _state.asStateFlow()

    init {
        // Load configuration on initialization
        viewModelScope.launch {
            loadConfiguration()
        }
    }

    /**
     * Load the startup configuration and update state accordingly.
     */
    private suspend fun loadConfiguration() {
        logger.info("Loading startup configuration from: $configDir")

        loadConfigUseCase(configDir).fold(
            ifLeft = { error ->
                // Configuration loading failed
                logger.error("Failed to load configuration: ${error.toMessage()}")
                _state.value = StartupState.Error(
                    message = "Failed to load configuration: ${error.toMessage()}",
                    canRetry = true
                )
            },
            ifRight = { config ->
                // Configuration loaded successfully
                when (config) {
                    is StartupConfiguration.NeedsSetup -> {
                        logger.info("Setup is required")
                        _state.value = StartupState.NeedsSetup(
                            configDir = configDir,
                            initialDto = config.initialDto
                        )
                    }
                    is StartupConfiguration.Ready -> {
                        logger.info("Configuration is ready (Server: ${config.config.network.serverUrl})")
                        _state.value = StartupState.Ready(config.config)
                    }
                }
            }
        )
    }

    /**
     * Called when setup is completed successfully.
     *
     * This transitions the state from NeedsSetup to Ready.
     *
     * @param config The validated application configuration from setup.
     */
    fun onSetupComplete(config: AppConfiguration) {
        logger.info("Setup completed, transitioning to Ready state")
        _state.value = StartupState.Ready(config)
    }

    /**
     * Retry loading configuration after an error.
     */
    fun retry() {
        logger.info("Retrying configuration load")
        _state.value = StartupState.Loading
        viewModelScope.launch {
            loadConfiguration()
        }
    }
}

