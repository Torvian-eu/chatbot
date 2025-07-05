package eu.torvian.chatbot.server.main

import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.config.Configurator
import java.io.BufferedReader
import java.io.InputStreamReader

val logger: Logger = LogManager.getLogger("ServerMain")

/**
 * Main entry point for the **standalone** chatbot server application.
 * Sets up logging and starts the server.
 */
fun main() {
    // TODO: Load server config from application.conf
    val serverConfig = ServerConfig("http", "localhost", 8080, "")
    val serverControlService = ServerControlServiceImpl(serverConfig)

    // Configure logging level once at application startup
    Configurator.setRootLevel(Level.DEBUG)

    try {
        runBlocking {
            try {
                serverControlService.startSuspend()
            } catch (e: Exception) {
                logger.error("Error during standalone server startup.", e)
            }

            println("Press Enter twice to exit, or type 'exit'.")

            val reader = BufferedReader(InputStreamReader(System.`in`))
            var emptyLineCount = 0

            try {
                while (true) {
                    val input = reader.readLine() ?: ""
                    if (input.trim().lowercase() == "exit") {
                        break
                    }
                    if (input.isBlank()) {
                        emptyLineCount++
                        if (emptyLineCount >= 2) {
                            println("Exiting on double return.")
                            break
                        }
                    } else {
                        emptyLineCount = 0
                        println("Invalid command. Press Enter twice or type 'exit' to exit.")
                    }
                }
            } catch (e: Exception) {
                println("An error occurred: ${e.message}")
            } finally {
                reader.close()
                serverControlService.stopSuspend(100, 3000)
            }

            println("Program exited.")
        }

    } catch (e: Exception) {
        logger.error("An unhandled error occurred: ${e.message}", e)
    }
}

