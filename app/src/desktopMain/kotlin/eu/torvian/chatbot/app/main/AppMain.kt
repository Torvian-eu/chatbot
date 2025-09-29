package eu.torvian.chatbot.app.main

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import eu.torvian.chatbot.app.compose.AppShell
import eu.torvian.chatbot.app.koin.appModule
import eu.torvian.chatbot.app.koin.desktopModule
import eu.torvian.chatbot.app.utils.misc.KmpLogger
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import eu.torvian.chatbot.common.security.EncryptionConfig
import org.koin.compose.KoinApplication
import kotlinx.io.files.Path
import java.util.Locale

private val logger: KmpLogger = createKmpLogger("DesktopAppMain")
private const val APPLICATION_NAME = "eu.torvian.chatbot"

/**
 * Main entry point for the Compose Desktop application.
 * Sets up logging, starts the application, and launches the UI.
 *
 * TODO: Read config objects from configuration file (config.json)
 */
fun main() {
    logger.info("Desktop application starting...")

    val baseUserDataStoragePath = getPlatformSpecificUserDataPath(APPLICATION_NAME)
    logger.info("Base user data storage path: $baseUserDataStoragePath")

    val appConfig = AppConfig(
        serverUrl = "http://localhost:8080",
        baseUserDataStoragePath = baseUserDataStoragePath.toString(),
        tokenStorageDir = "tokens"
    )

    val encryptionConfig = EncryptionConfig(
        // TODO: **IMPORTANT:** Change this key in production!
        masterKeys = mapOf(1 to "G2CgJOQQtIC+yfz+LLoDp/osBLUVzW9JE9BrQA0dQFo="),
        keyVersion = 1
    )

    application {
        Window(onCloseRequest = ::exitApplication, title = "Chatbot Desktop App") {
            KoinApplication(application = {
                modules(
                    desktopModule(appConfig, encryptionConfig),
                    appModule(appConfig.serverUrl)
                )
            }) {
                AppShell()
            }
        }
    }
}

/**
 * Determines the platform-specific base directory for user application data.
 * This function adheres to common operating system conventions for storing user-specific data.
 *
 * @param appName The name of the application, used to create a subdirectory within the platform-specific location.
 * @return A [Path] object representing the base user data storage directory.
 */
private fun getPlatformSpecificUserDataPath(appName: String): Path {
    // Detect the operating system
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)

    // Construct the path based on the OS
    return when {
        os.contains("win") -> {
            // Windows: Use %APPDATA% (e.g., C:\Users\<username>\AppData\Roaming)
            // Fallback to %USERPROFILE%\AppData\Roaming if APPDATA environment variable is not set.
            val appData = System.getenv("APPDATA")
            if (appData != null) {
                Path(appData, appName)
            } else {
                Path(System.getProperty("user.home"), "AppData", "Roaming", appName)
            }
        }
        os.contains("mac") || os.contains("darwin") -> {
            // macOS: Use ~/Library/Application Support/<appName>
            Path(System.getProperty("user.home"), "Library", "Application Support", appName)
        }
        else -> {
            // Linux/Unix/Other: Follow XDG Base Directory Specification.
            // Use $XDG_CONFIG_HOME (defaulting to ~/.config/<appName>) for configuration/data.
            val xdgConfigHome = System.getenv("XDG_CONFIG_HOME")
            if (xdgConfigHome != null) {
                Path(xdgConfigHome, appName)
            } else {
                Path(System.getProperty("user.home"), ".config", appName)
            }
        }
    }
}