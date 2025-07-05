package eu.torvian.chatbot.app.main

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import eu.torvian.chatbot.app.compose.AppShell
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.config.Configurator

private val logger: Logger = LogManager.getLogger("DesktopAppMain")

/**
 * Main entry point for the Compose Desktop application.
 * Sets up logging, starts the application, and launches the UI.
 */
fun main() {
    Configurator.setRootLevel(Level.DEBUG)
    logger.info("Desktop application starting...")

    application {
        Window(onCloseRequest = ::exitApplication, title = "Chatbot Desktop App") {
            AppShell()
        }
    }
}