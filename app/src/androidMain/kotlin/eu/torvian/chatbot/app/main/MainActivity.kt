package eu.torvian.chatbot.app.main

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.activity.compose.LocalActivity
import eu.torvian.chatbot.app.compose.AppShell
import eu.torvian.chatbot.app.compose.setup.SetupScreen
import eu.torvian.chatbot.app.compose.startup.StartupErrorScreen
import eu.torvian.chatbot.app.compose.startup.StartupLoadingScreen
import eu.torvian.chatbot.app.config.FileSystemClientConfigLoader
import eu.torvian.chatbot.app.koin.androidModule
import eu.torvian.chatbot.app.koin.appModule
import eu.torvian.chatbot.app.koin.databaseModule
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import eu.torvian.chatbot.app.viewmodel.startup.LoadStartupConfigurationUseCase
import eu.torvian.chatbot.app.viewmodel.startup.StartupState
import eu.torvian.chatbot.app.viewmodel.startup.StartupViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.compose.KoinApplication

private val logger = createKmpLogger("AndroidMainActivity")

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Config files live in the app's private files directory, e.g.:
        // /data/data/eu.torvian.chatbot/files/config/
        val configDir = "${applicationContext.filesDir.absolutePath}/config"

        setContent {
            AppLifecycleManager(configDir, this@MainActivity)
        }
    }
}

/**
 * Composable that manages the application's lifecycle states.
 *
 * This is a pure UI component — all business logic is in [StartupViewModel].
 * It transitions from Loading → NeedsSetup/Ready/Error based on ViewModel state.
 *
 * @param configDir The path to the configuration directory in private app storage.
 * @param context The Android context for Koin initialization.
 */
@Composable
fun AppLifecycleManager(configDir: String, context: Context) {
    val configLoader = remember { FileSystemClientConfigLoader() }
    val viewModel = remember {
        StartupViewModel(
            configDir = configDir,
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
            val activity = LocalActivity.current
            StartupErrorScreen(
                errorMessage = currentState.message,
                canRetry = currentState.canRetry,
                onRetry = {
                    logger.info("User requested retry of configuration loading.")
                    viewModel.retry()
                },
                onExit = {
                    logger.info("User requested application exit from error screen.")
                    activity?.finish()
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
                androidContext(context)
                modules(
                    androidModule(currentState.config),
                    databaseModule,
                    appModule(currentState.config)
                )
            }) {
                AppShell()
            }
        }
    }
}