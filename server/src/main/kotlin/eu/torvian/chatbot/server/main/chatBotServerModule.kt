package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.common.misc.di.KoinDIContainer
import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.domain.security.EncryptionConfig
import eu.torvian.chatbot.server.koin.*
import eu.torvian.chatbot.server.ktor.configureKtor
import eu.torvian.chatbot.server.ktor.routes.ApiRoutesKtor
import eu.torvian.chatbot.server.utils.misc.DIContainerKey
import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.get
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin

/**
 * Configures the Ktor application module with all necessary plugins, DI, database, and routing.
 * This function is an extension on [Application], intended to be passed to `embeddedServer`.
 */
fun Application.chatBotServerModule() {
    // Configure Koin DI FIRST, as plugins and routing will depend on it
    configureKoin()

    // Configure Ktor (general plugins like content negotiation, status pages, etc.)
    configureKtor()

    // Configure CORS
    // This allows the WASM app to make requests to the server.
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
    }

    // Configure the database schema
    configureDatabase()

    // Configure routing
    configureRouting()
}

/**
 * Configures Koin for dependency injection within the Application.
 */
fun Application.configureKoin() {
    // Configuration values (e.g., database credentials, encryption keys)
    val databaseConfig = DatabaseConfig(
        vendor = "sqlite",
        type = "file",
        filepath = null, // null means in the current working directory
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
            mainModule(this@configureKoin) // Pass Application instance for Ktor specific bindings if needed
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
//        // Drop and create tables - adjust as needed for production
//        dataManager.dropTables()
        // Only create tables if the database is empty
        if (dataManager.isDatabaseEmpty())
            dataManager.createTables()
        logger.info("Database initialized successfully.")
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