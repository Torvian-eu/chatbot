package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.common.misc.di.KoinDIContainer
import eu.torvian.chatbot.common.security.EncryptionConfig
import eu.torvian.chatbot.server.domain.config.CorsConfig
import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.domain.config.ReverseProxyConfig
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.koin.*
import eu.torvian.chatbot.server.ktor.configureKtor
import eu.torvian.chatbot.server.ktor.routes.ApiRoutesKtor
import eu.torvian.chatbot.server.ktor.routes.configureWorkerWebSocketRoutes
import eu.torvian.chatbot.server.worker.protocol.codec.WorkerServerWebSocketMessageCodec
import eu.torvian.chatbot.server.worker.protocol.routing.WorkerServerIncomingMessageRouter
import eu.torvian.chatbot.server.worker.command.pending.PendingWorkerCommandRegistry
import eu.torvian.chatbot.server.worker.session.WorkerSessionRegistry
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
 * @param corsConfig CORS configuration
 * @param reverseProxyConfig Reverse proxy configuration for forwarded headers support
 */
fun Application.chatBotServerModule(
    databaseConfig: DatabaseConfig,
    encryptionConfig: EncryptionConfig,
    jwtConfig: JwtConfig,
    corsConfig: CorsConfig,
    reverseProxyConfig: ReverseProxyConfig
) {
    // Configure Koin DI FIRST, as plugins and routing will depend on it
    configureKoin(databaseConfig, encryptionConfig, jwtConfig)

    // Configure Ktor (general plugins like content negotiation, status pages, etc.)
    configureKtor(get(), get(), reverseProxyConfig)

    // Configure CORS from explicit allowlist.
    install(CORS) {
        corsConfig.allowedOrigins.forEach { origin ->
            allowHost(origin.hostWithOptionalPort, schemes = listOf(origin.scheme))
        }

        // Explicitly allow methods that the API will handle
        allowMethod(HttpMethod.Options) // Crucial for preflight requests
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)

        // Explicitly allow headers that the client might send
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        // If there are any other custom headers, add them here:
        // allowHeader("X-Custom-Header")

        // set maxAge to cache preflight responses
        maxAgeInSeconds = 3600 // Cache preflight response for 1 hour
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
        val databaseMigrator: DatabaseMigrator = get()
        val initializationCoordinator: InitializationCoordinator = get()

        // Migrations are always executed so existing databases can evolve safely.
        databaseMigrator.migrate()

        // Perform initial setup for all system components (idempotent per initializer).
        initializationCoordinator.runAllInitializers().fold(
            ifLeft = { error ->
                logger.error("Initial setup failed: $error")
                throw IllegalStateException("Failed to perform initial setup: $error")
            },
            ifRight = {
                logger.info("Initial setup completed successfully for all components")
            }
        )
    }
}

/**
 * Configures routing for the Ktor application.
 */
fun Application.configureRouting() {
    val apiRoutesKtor: ApiRoutesKtor = get()
    val workerSessionRegistry: WorkerSessionRegistry = get()
    val workerMessageCodec: WorkerServerWebSocketMessageCodec = get()
    val workerMessageRouter: WorkerServerIncomingMessageRouter = get()
    val pendingWorkerCommandRegistry: PendingWorkerCommandRegistry = get()
    routing {
        apiRoutesKtor.configureAllRoutes(this)
        configureWorkerWebSocketRoutes(
            workerSessionRegistry = workerSessionRegistry,
            messageCodec = workerMessageCodec,
            messageRouter = workerMessageRouter,
            pendingCommandRegistry = pendingWorkerCommandRegistry
        )
    }
}