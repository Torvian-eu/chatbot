package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.common.misc.di.KoinDIContainer
import eu.torvian.chatbot.common.security.EncryptionConfig
import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.koin.*
import eu.torvian.chatbot.server.ktor.configureKtor
import eu.torvian.chatbot.server.ktor.routes.ApiRoutesKtor
import eu.torvian.chatbot.server.service.setup.InitializationCoordinator
import eu.torvian.chatbot.server.utils.misc.DIContainerKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.koin.ktor.ext.get
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin

private val logger: Logger = LogManager.getLogger("chatBotServerModule")

/**
 * Configures the Ktor application module with all necessary plugins, DI, database, and routing.
 * This function is an extension on [Application], intended to be passed to `embeddedServer`.
 *
 * @param databaseConfig Database configuration
 * @param encryptionConfig Encryption configuration
 * @param jwtConfig JWT configuration
 */
fun Application.chatBotServerModule(
    databaseConfig: DatabaseConfig,
    encryptionConfig: EncryptionConfig,
    jwtConfig: JwtConfig
) {
    // Configure Koin DI FIRST, as plugins and routing will depend on it
    configureKoin(databaseConfig, encryptionConfig, jwtConfig)

    // Configure Ktor (general plugins like content negotiation, status pages, etc.)
    configureKtor(get(), get())

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
 *
 * @param databaseConfig Database configuration
 * @param encryptionConfig Encryption configuration
 * @param jwtConfig JWT configuration
 */
fun Application.configureKoin(
    databaseConfig: DatabaseConfig,
    encryptionConfig: EncryptionConfig,
    jwtConfig: JwtConfig
) {
    // Initialize Koin plugin with defined modules
    install(Koin) {
        modules(
            configModule(databaseConfig, encryptionConfig, jwtConfig),
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
        val initializationCoordinator: InitializationCoordinator = get()

        // Only create tables if the database is empty
        if (dataManager.isDatabaseEmpty()) {
            dataManager.createTables()
            logger.info("Database tables created successfully.")

            // Perform initial setup for all system components
            initializationCoordinator.runAllInitializers().fold(
                ifLeft = { error ->
                    logger.error("Initial setup failed: $error")
                    throw IllegalStateException("Failed to perform initial setup: $error")
                },
                ifRight = {
                    logger.info("Initial setup completed successfully for all components")
                }
            )
        } else {
            logger.info("Database already exists, skipping table creation and initial setup.")
        }
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