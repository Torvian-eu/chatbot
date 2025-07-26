package eu.torvian.chatbot.app.main

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import eu.torvian.chatbot.app.compose.AppShell
import eu.torvian.chatbot.app.koin.appModule
import eu.torvian.chatbot.app.utils.misc.KmpLogger
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import org.koin.compose.KoinApplication

private val logger: KmpLogger = createKmpLogger("DesktopAppMain")

/**
 * Main entry point for the Compose Desktop application.
 * Sets up logging, starts the application, and launches the UI.
 */
fun main() {
    logger.info("Desktop application starting...")

    application {
        Window(onCloseRequest = ::exitApplication, title = "Chatbot Desktop App") {
            KoinApplication(application = {
                modules(appModule("http://localhost:8080"))
            }) {
                AppShell()
            }
        }
    }
}