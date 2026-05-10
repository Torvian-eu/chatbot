package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.common.misc.di.KoinDIContainer
import eu.torvian.chatbot.server.config.AppConfiguration
import eu.torvian.chatbot.server.koin.*
import eu.torvian.chatbot.server.ktor.configureKtor
import eu.torvian.chatbot.server.ktor.routes.ApiRoutesKtor
import eu.torvian.chatbot.server.ktor.routes.configureWorkerWebSocketRoutes
import eu.torvian.chatbot.server.ktor.routes.configurePublicAuthRoutes
import eu.torvian.chatbot.server.service.security.AuthenticationService
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
 * @param config The root application configuration.
 */
fun Application.chatBotServerModule(config: AppConfiguration) {
    // Configure Koin DI FIRST, as plugins and routing will depend on it
    configureKoin(config)

    // Configure Ktor (general plugins like content negotiation, status pages, etc.)
    configureKtor(config.jwt, get(), config.reverseProxy)

    // Configure CORS from explicit allowlist.
    install(CORS) {
        config.cors.allowedOrigins.forEach { origin ->
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
 * @param config The root application configuration.
 */
fun Application.configureKoin(config: AppConfiguration) {
    // Initialize Koin plugin with defined modules
    install(Koin) {
        modules(
            configModule(config),
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
    val authenticationService: AuthenticationService = get()
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
        configurePublicAuthRoutes(authenticationService)
    }
}