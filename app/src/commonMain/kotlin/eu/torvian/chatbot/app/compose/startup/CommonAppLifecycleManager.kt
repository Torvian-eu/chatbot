package eu.torvian.chatbot.app.compose.startup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.torvian.chatbot.app.compose.AppShell
import eu.torvian.chatbot.app.compose.AppTheme
import eu.torvian.chatbot.app.compose.setup.SetupScreen
import eu.torvian.chatbot.app.config.ClientConfigLoader
import eu.torvian.chatbot.app.config.AppConfiguration
import eu.torvian.chatbot.app.viewmodel.AppViewModel
import eu.torvian.chatbot.app.viewmodel.startup.LoadStartupConfigurationUseCase
import eu.torvian.chatbot.app.viewmodel.startup.StartupState
import eu.torvian.chatbot.app.viewmodel.startup.StartupViewModel
import org.koin.compose.KoinApplication
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.KoinApplication

/**
 * Common composable that manages startup, theming, and dependency injection for all platforms.
 *
 * This composable centralizes the startup state machine logic that was previously duplicated
 * across Android, Desktop, and WasmJS platforms. It handles the Loading → NeedsSetup/Ready/Error
 * state transitions and initializes Koin when the app is ready.
 *
 * @param configDir The path to the configuration directory.
 * @param configLoader The platform-specific configuration loader implementation.
 * @param onExit Callback invoked when the user requests to exit from an error state.
 * @param koinApp A lambda that receives the KoinApplication and AppConfiguration to set up the Koin modules.
 *                This allows each platform to provide its own module definitions while still using the shared startup logic.
 */
@Composable
fun CommonAppLifecycleManager(
    configDir: String,
    configLoader: ClientConfigLoader,
    onExit: () -> Unit,
    koinApp:  KoinApplication.(AppConfiguration) -> Unit
) {
    val startupViewModel = remember {
        StartupViewModel(
            configDir = configDir,
            loadConfigUseCase = LoadStartupConfigurationUseCase(configLoader)
        )
    }

    // Collect state from ViewModel
    val state by startupViewModel.state.collectAsState()

    when (val currentState = state) {
        is StartupState.Loading -> {
            // Show proper loading screen with visual feedback
            AppTheme {
                StartupLoadingScreen()
            }
        }

        is StartupState.Error -> {
            // Show user-friendly error screen with retry option
            AppTheme {
                StartupErrorScreen(
                    errorMessage = currentState.message,
                    canRetry = currentState.canRetry,
                    onRetry = {
                        startupViewModel.retry()
                    },
                    onExit = onExit
                )
            }
        }

        is StartupState.NeedsSetup -> {
            // Render the SetupScreen
            AppTheme {
                SetupScreen(
                    configDir = currentState.configDir,
                    configLoader = configLoader,
                    initialDto = currentState.initialDto,
                    onComplete = { appConfiguration ->
                        startupViewModel.onSetupComplete(appConfiguration)
                    }
                )
            }
        }

        is StartupState.Ready -> {
            KoinApplication(application = {
                koinApp(this, currentState.config)
            }) {
                val appViewModel: AppViewModel = koinViewModel()
                val currentTheme by appViewModel.currentTheme.collectAsState()

                AppTheme(currentTheme) {
                    AppShell()
                }
            }
        }
    }
}
