package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.common.misc.di.DIContainerKey
import eu.torvian.chatbot.common.misc.di.KoinDIContainer
import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.domain.security.EncryptionConfig
import eu.torvian.chatbot.server.koin.*
import eu.torvian.chatbot.server.ktor.routes.ApiRoutesKtor
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.config.Configurator
import org.koin.ktor.ext.get
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin

private val logger: Logger = LogManager.getLogger("ServerMain")

/**
 * Main entry point for the chatbot server application.
 * Sets up Koin dependency injection, configures the database,
 * installs necessary plugins, and starts the Ktor server.
 */
fun main() {
    // Configure logging level
    Configurator.setRootLevel(Level.DEBUG)
    logger.info("Starting Chatbot Server...")

    // Start Ktor embedded server
    embeddedServer(Netty, port = 8080, host = "localhost") {
        // Configure Koin DI FIRST, as plugins and routing will depend on it
        configureKoin()

        // Install the ContentNegotiation plugin for JSON serialization
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = false
            })
        }

        // Install Resources plugin for type-safe routing
        install(Resources)

        // Configure the database schema (usually blocking)
        configureDatabase()

        // Configure routing
        configureRouting()

        // Log successful startup information
        logger.info("Server started successfully on http://localhost:8080")
        logger.info("API endpoints available at http://localhost:8080/api/v1/")
    }.start(wait = true) // Start the server and wait until it's stopped
}

/**
 * Configures Koin for dependency injection within the Application.
 */
fun Application.configureKoin() {
    // Configuration values (e.g., database credentials, encryption keys)
    val databaseConfig = DatabaseConfig(
        vendor = "sqlite",
        type = "file", // Use file-based SQLite for persistence
        filepath = null,
        filename = "chatbot.db",
        user = null,
        password = null
    )
    val encryptionConfig = EncryptionConfig(
        keyVersion = 1,
        masterKey = "default-master-key-change-in-production" // **IMPORTANT:** Change this in production!
    )

    // Initialize Koin plugin with defined modules
    install(Koin) {
        modules(
            configModule(databaseConfig, encryptionConfig),
            databaseModule(),
            miscModule(),
            daoModule(),
            serviceModule(),
            mainModule(this@configureKoin)
        )
    }

    // Store the DI container in the application attributes for later manual access if needed
    val container = KoinDIContainer(getKoin())
    attributes.put(DIContainerKey, container)
}

/**
 * Configures the database schema for the application.
 */
fun Application.configureDatabase() {
    runBlocking {
        val dataManager: DataManager = get()
        // Drop and create tables - adjust as needed for production (migrations!)
        dataManager.dropTables()
        dataManager.createTables()
        logger.info("Database initialized successfully")
    }
}

/**
 * Configures routing for the Ktor application.
 */
fun Application.configureRouting() {
    val apiRoutesKtor: ApiRoutesKtor = get()
    routing {
        apiRoutesKtor.configureAllRoutes(this)
    }
}
