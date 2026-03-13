package eu.torvian.chatbot.app.main

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import eu.torvian.chatbot.app.compose.AppShell
import eu.torvian.chatbot.app.compose.setup.SetupScreen
import eu.torvian.chatbot.app.compose.startup.StartupErrorScreen
import eu.torvian.chatbot.app.compose.startup.StartupLoadingScreen
import eu.torvian.chatbot.app.config.WebStorageClientConfigLoader
import eu.torvian.chatbot.app.koin.appModule
import eu.torvian.chatbot.app.koin.wasmJsModule
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import eu.torvian.chatbot.app.viewmodel.startup.LoadStartupConfigurationUseCase
import eu.torvian.chatbot.app.viewmodel.startup.StartupState
import eu.torvian.chatbot.app.viewmodel.startup.StartupViewModel
import kotlinx.browser.window
import org.koin.compose.KoinApplication

private val logger = createKmpLogger("WasmJsAppMain")

/**
 * The localStorage namespace used as the config directory for the WasmJS platform.
 * Config entries are stored as keys prefixed with this value, e.g.
 * `"eu.torvian.chatbot/config.json"`.
 */
private const val CONFIG_DIR = "eu.torvian.chatbot"

/**
 * Main entry point for the WasmJS application.
 *
 * Delegates all startup logic to [AppLifecycleManager], which drives the same
 * Loading → NeedsSetup/Ready/Error state machine used on the desktop target.
 * Configuration is loaded from and saved to the browser's localStorage via
 * [WebStorageClientConfigLoader].
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    logger.info("WasmJS application starting...")
    ComposeViewport {
        AppLifecycleManager()
    }
}

/**
 * Composable that manages the application's lifecycle states.
 *
 * This is a pure UI component — all business logic is in [StartupViewModel].
 * It transitions from Loading → NeedsSetup/Ready/Error based on ViewModel state.
 */
@Composable
fun AppLifecycleManager() {
    val configLoader = remember { WebStorageClientConfigLoader() }
    val viewModel = remember {
        StartupViewModel(
            configDir = CONFIG_DIR,
            loadConfigUseCase = LoadStartupConfigurationUseCase(configLoader)
        )
    }

    val state by viewModel.state.collectAsState()

    when (val currentState = state) {
        is StartupState.Loading -> {
            logger.debug("App is in Loading state.")
            StartupLoadingScreen()
        }

        is StartupState.Error -> {
            logger.error("Configuration error: ${currentState.message}")
            StartupErrorScreen(
                errorMessage = currentState.message,
                canRetry = currentState.canRetry,
                onRetry = {
                    logger.info("User requested retry of configuration loading.")
                    viewModel.retry()
                },
                onExit = {
                    logger.info("User requested page reload from error screen.")
                    window.location.reload()
                }
            )
        }

        is StartupState.NeedsSetup -> {
            logger.info("Displaying SetupScreen for initial configuration.")
            SetupScreen(
                configDir = currentState.configDir,
                configLoader = configLoader,
                initialDto = currentState.initialDto,
                onComplete = { appConfiguration ->
                    logger.info("Setup completed successfully. Transitioning to Ready state.")
                    viewModel.onSetupComplete(appConfiguration)
                }
            )
        }

        is StartupState.Ready -> {
            logger.info("Configuration ready. Initializing Koin and launching main application.")
            KoinApplication(application = {
                modules(
                    wasmJsModule(currentState.config),
                    appModule(currentState.config)
                )
            }) {
                AppShell()
            }
        }
    }
}