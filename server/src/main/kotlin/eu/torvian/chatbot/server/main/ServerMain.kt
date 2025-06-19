package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.common.misc.di.DIContainerKey
import eu.torvian.chatbot.common.misc.di.KoinDIContainer
import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.domain.security.EncryptionConfig
import eu.torvian.chatbot.server.koin.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
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
 * Main entry point for the server application.
 * Sets up dependency injection, database, and starts the Ktor server.
 */
fun main() {
    Configurator.setRootLevel(Level.DEBUG)
    logger.info("Starting Chatbot Server...")

    // Start Ktor server
    embeddedServer(Netty, port = 8080, host = "localhost") {
        configureKoin()
        configureDatabase()
        configureSerialization()
        configureRouting()

        logger.info("Server started successfully on http://localhost:8080")
        logger.info("API endpoints available at http://localhost:8080/api/v1/")
    }.start(wait = true)
}

/**
 * Configures Koin for dependency injection.
 */
fun Application.configureKoin() {
    // Configuration
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
        masterKey = "default-master-key-change-in-production"
    )

    // Initialize Koin
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

    // Store the DI container in the application attributes for later use
    val container = KoinDIContainer(getKoin())
    attributes.put(DIContainerKey, container)
}

/**
 * Configures the database schema.
 */
fun Application.configureDatabase() {
    runBlocking {
        val dataManager: DataManager = get()
        dataManager.dropTables()
        dataManager.createTables()
        logger.info("Database initialized successfully")
    }
}

/**
 * Configures JSON serialization for the Ktor application.
 */
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = false
        })
    }
}

/**
 * Configures routing for the Ktor application.
 */
fun Application.configureRouting() {
    val apiRoutes: ApiRoutes = get()
    apiRoutes.configureRouting()
}
