package eu.torvian.chatbot.app.main

import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import eu.torvian.chatbot.app.compose.AppShell
import eu.torvian.chatbot.app.compose.setup.SetupScreen
import eu.torvian.chatbot.app.compose.startup.StartupErrorScreen
import eu.torvian.chatbot.app.compose.startup.StartupLoadingScreen
import eu.torvian.chatbot.app.config.FileSystemClientConfigLoader
import eu.torvian.chatbot.app.koin.appModule
import eu.torvian.chatbot.app.koin.desktopModule
import eu.torvian.chatbot.app.utils.misc.KmpLogger
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import eu.torvian.chatbot.app.viewmodel.startup.LoadStartupConfigurationUseCase
import eu.torvian.chatbot.app.viewmodel.startup.StartupState
import eu.torvian.chatbot.app.viewmodel.startup.StartupViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.koin.compose.KoinApplication
import java.util.Locale
import kotlin.system.exitProcess

/** The unique application ID, used for platform-specific user data paths. */
private const val APP_ID = "eu.torvian.chatbot"

/** Environment variable name for specifying custom config directory location. */
private const val CONFIG_DIR_ENV_VAR = "CHATBOT_CONFIG_DIR"

private val logger: KmpLogger = createKmpLogger("DesktopAppMain")

/**
 * Main entry point for the Chatbot Client Desktop application.
 *
 * This function determines the configuration directory location and launches the application.
 * The actual configuration loading and setup flow is handled by StartupViewModel.
 *
 * **Configuration Directory Resolution (in priority order):**
 * 1. **Environment Variable:** If `CHATBOT_CONFIG_DIR` is set, use that path directly.
 * 2. **Current Working Directory:** If `./config/config.json` exists, use `./config`.
 * 3. **OS-Specific User Data Path:** Fall back to the platform-specific location
 *    (e.g., `%APPDATA%\eu.torvian.chatbot\config` on Windows).
 *
 * **Developer Note:** During development, you can either:
 * - Set the `CHATBOT_CONFIG_DIR` environment variable to point to your config directory
 * - Create a `./config/` directory in the project root with pre-configured files
 *   (`config.json`, `secrets.json`, and `setup.json`)
 */
fun main() = runBlocking {
    logger.info("Chatbot Client Desktop application starting...")

    // 1. Resolve the Configuration Anchor:
    //    Priority: Environment variable > CWD > OS-specific path
    val configDir = resolveConfigDirectory()

    // 2. Launch the Compose Multiplatform application.
    application {
        Window(onCloseRequest = ::exitApplication, title = "Torvian chatbot") {
            // AppLifecycleManager handles the startup state machine via StartupViewModel
            AppLifecycleManager(configDir)
        }
    }
}

/**
 * Composable that manages the application's lifecycle states within a single window.
 *
 * This is a pure UI component - all business logic is in StartupViewModel.
 * It transitions from Loading → NeedsSetup/Ready/Error based on ViewModel state.
 *
 * @param configDir The multiplatform [Path] to the resolved configuration anchor directory.
 */
@Composable
fun AppLifecycleManager(configDir: Path) {
    val configLoader = remember { FileSystemClientConfigLoader() }
    // Create ViewModel (manual creation, not Koin - Koin isn't initialized yet)
    val viewModel = remember {
        StartupViewModel(
            configDir = configDir.toString(),
            loadConfigUseCase = LoadStartupConfigurationUseCase(configLoader)
        )
    }

    // Collect state from ViewModel
    val state by viewModel.state.collectAsState()

    // Render based on state
    when (val currentState = state) {
        is StartupState.Loading -> {
            // Show proper loading screen with visual feedback
            logger.debug("App is in Loading state.")
            StartupLoadingScreen()
        }

        is StartupState.Error -> {
            // Show user-friendly error screen with retry option
            logger.error("Configuration error: ${currentState.message}")
            StartupErrorScreen(
                errorMessage = currentState.message,
                canRetry = currentState.canRetry,
                onRetry = {
                    logger.info("User requested retry of configuration loading.")
                    viewModel.retry()
                },
                onExit = {
                    logger.info("User requested application exit from error screen.")
                    exitProcess(1)
                }
            )
        }

        is StartupState.NeedsSetup -> {
            // Render the SetupScreen
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
            // Configuration is ready, initialize Koin and render the main application shell.
            logger.info("Configuration ready. Initializing Koin and launching main application.")
            KoinApplication(application = {
                modules(
                    desktopModule(currentState.config),
                    appModule(currentState.config)
                )
            }) {
                AppShell() // The main UI of the application
            }
        }
    }
}

/**
 * Resolves the configuration directory location based on the following priority:
 * 1. Environment variable CHATBOT_CONFIG_DIR (if set and exists)
 * 2. Current working directory ./config (if exists)
 * 3. OS-specific user data path (default fallback)
 *
 * @return The resolved configuration directory path
 */
private fun resolveConfigDirectory(): Path {
    // 1. Check environment variable (highest priority for development flexibility)
    System.getenv(CONFIG_DIR_ENV_VAR)?.takeIf { it.isNotEmpty() }?.let { envConfigDir ->
        val envPath = Path(envConfigDir)
        if (SystemFileSystem.exists(envPath)) {
            logger.info("Config anchor resolved from environment variable $CONFIG_DIR_ENV_VAR: $envPath")
            return envPath
        } else {
            logger.warn("Environment variable $CONFIG_DIR_ENV_VAR is set to '$envPath', but the directory does not exist. Falling back to next option.")
        }
    }

    // 2. Check current working directory (common for portable installations)
    val cwdConfigPath = Path("./config")
    val cwdConfigFile = Path(cwdConfigPath, "config.json")
    if (SystemFileSystem.exists(cwdConfigFile)) {
        logger.info("Config anchor resolved to Current Working Directory: $cwdConfigPath")
        return cwdConfigPath
    }

    // 3. Use OS-specific user data path (standard for production)
    val osSpecificBasePath = getPlatformSpecificUserDataPath(APP_ID)
    val osSpecificConfigPath = Path(osSpecificBasePath, "config")
    logger.info("Config anchor resolved to OS-specific User Data Path: $osSpecificConfigPath")
    return osSpecificConfigPath
}

/**
 * Determines the platform-specific base directory for user application data.
 *
 * This function adheres to common operating system conventions for storing user-specific data,
 * ensuring that user configurations and data are stored in a standard location.
 *
 * **Platform-specific paths:**
 * - **Windows:** `%APPDATA%\<appId>` (e.g., `C:\Users\<username>\AppData\Roaming\eu.torvian.chatbot`)
 * - **macOS:** `~/Library/Application Support/<appId>`
 * - **Linux/Unix:** `$XDG_CONFIG_HOME/<appId>` (defaults to `~/.config/<appId>`)
 *
 * @param appId The unique application ID (e.g., "eu.torvian.chatbot"), used to create a subdirectory
 *              within the platform-specific location.
 * @return A multiplatform [Path] object representing the base user data storage directory.
 */
fun getPlatformSpecificUserDataPath(appId: String): Path {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    val userHome = System.getProperty("user.home")
        ?: throw IllegalStateException("user.home system property is not set")

    val basePath = when {
        os.contains("win") -> {
            // Windows: Use %APPDATA% (e.g., C:\Users\<username>\AppData\Roaming)
            System.getenv("APPDATA") ?: "$userHome\\AppData\\Roaming"
        }
        os.contains("mac") || os.contains("darwin") -> {
            // macOS: Use ~/Library/Application Support
            "$userHome/Library/Application Support"
        }
        else -> {
            // Linux/Unix/Other: Follow XDG Base Directory Specification
            // Use $XDG_CONFIG_HOME (defaulting to ~/.config)
            System.getenv("XDG_CONFIG_HOME") ?: "$userHome/.config"
        }
    }

    return Path(basePath, appId)
}