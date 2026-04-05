package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.common.security.EncryptionConfig
import eu.torvian.chatbot.server.domain.config.CorsConfig
import eu.torvian.chatbot.server.domain.config.DatabaseConfig
import eu.torvian.chatbot.server.domain.config.NetworkConfig
import eu.torvian.chatbot.server.domain.config.ServerConnectorType
import eu.torvian.chatbot.server.domain.config.SslConfig
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.service.security.CertificateManager
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.jakarta.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock

/**
 * Default timeout for server startup in milliseconds.
 */
private const val SERVER_STARTUP_TIMEOUT_MILLIS = 15_000L // 15 seconds

/**
 * Implementation of [ServerControlService] that manages the lifecycle of a Ktor server.
 *
 * This service encapsulates the Ktor [EmbeddedServer] instance, providing methods to start,
 * stop, and monitor its status. It ensures that the primary server URI is correctly identified
 * and that startup failures or timeouts are properly handled.
 *
 * @property networkConfig The validated network configuration.
 * @property sslConfig Optional SSL configuration (required for HTTPS/HTTP_AND_HTTPS).
 * @property certificateManager Optional manager for SSL certificate operations.
 * @property databaseConfig Database configuration passed to the application module.
 * @property encryptionConfig Encryption configuration passed to the application module.
 * @property jwtConfig JWT configuration passed to the application module.
 * @property corsConfig CORS configuration passed to the application module.
 */
class ServerControlServiceImpl(
    val networkConfig: NetworkConfig,
    val sslConfig: SslConfig?,
    private val certificateManager: CertificateManager?,
    private val databaseConfig: DatabaseConfig,
    private val encryptionConfig: EncryptionConfig,
    private val jwtConfig: JwtConfig,
    private val corsConfig: CorsConfig
) : ServerControlService {
    private val logger: Logger = LogManager.getLogger(this::class.java)

    /** The internal Ktor server instance. */
    private var internalServerInstance: EmbeddedServer<*, *>? = null

    /** Backing field for [getServerInfo]. */
    private var _serverInfo: ServerInstanceInfo? = null

    /** Mutable state flow to publish server status changes. */
    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.NotStarted)

    /** Publicly exposed read-only flow of server status. */
    override val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    /**
     * Starts the server in a suspending, non-blocking manner.
     *
     * This method initiates the Ktor engine startup and then suspends until the server
     * is fully initialized and ready to accept connections (i.e., [serverStatus] becomes
     * [ServerStatus.Started]). If the server fails to start or times out, an exception
     * is thrown.
     *
     * @throws IllegalStateException if the server is already starting/started or fails to acquire connectors.
     * @throws TimeoutCancellationException if server startup exceeds [SERVER_STARTUP_TIMEOUT_MILLIS].
     * @throws Exception if an unexpected error occurs during Ktor engine startup.
     */
    override suspend fun startSuspend() {
        // Prevent starting multiple times if already starting or started
        if (_serverStatus.value !is ServerStatus.NotStarted && _serverStatus.value !is ServerStatus.Stopped && _serverStatus.value !is ServerStatus.Error) {
            logger.warn("Server start requested, but status is ${_serverStatus.value}. Ignoring.")
            return
        }
        logger.info("Starting Chatbot Server...")
        _serverStatus.value = ServerStatus.Starting // Indicate starting state early

        val server = createEmbeddedServer(
            Jetty, port = networkConfig.port, host = networkConfig.host,
            onStarting = { logger.info("Ktor engine starting...") },
            onStarted = { app ->
                // This callback is invoked by Ktor when its internal engine is fully started.
                // We launch a coroutine to determine the resolved connectors and update status.
                CoroutineScope(Dispatchers.IO).launch {
                    val connectors = app.engine.resolvedConnectors()
                    if (connectors.isEmpty()) {
                        val errorMsg = "Failed to determine any active connectors after Ktor engine started."
                        logger.error(errorMsg)
                        _serverStatus.value = ServerStatus.Error(IllegalStateException(errorMsg))
                        return@launch
                    }
                    val connectorInfo = connectors.joinToString { c ->
                        val scheme = if (c.type == ConnectorType.HTTPS) "https" else "http"
                        "$scheme://${c.host}:${c.port}"
                    }
                    logger.info("Ktor engine listening on: $connectorInfo")

                    // Determine the primary connector (HTTPS preferred) and create ServerInstanceInfo
                    val primaryConnector = connectors.find { c -> c.type == ConnectorType.HTTPS } ?: connectors.first()
                    val info = ServerInstanceInfo(
                        scheme = if (primaryConnector.type == ConnectorType.HTTPS) "https" else "http",
                        host = primaryConnector.host,
                        port = primaryConnector.port,
                        path = networkConfig.path,
                        startTime = Clock.System.now()
                    )
                    _serverInfo = info // Update the internal backing field for the getter
                    _serverStatus.value = ServerStatus.Started(info) // Emit the Started status
                    logger.info("Primary server URI identified: ${info.baseUri}")
                }
            },
            onStopping = { logger.info("Ktor engine stopping...") },
            onStopped = {
                logger.info("Ktor engine stopped gracefully.")
                _serverInfo = null // Clear info when stopped
                _serverStatus.value = ServerStatus.Stopped // Emit stopped status
            },
        )
        internalServerInstance = server // Store the Ktor server instance

        try {
            // Start Ktor's internal engine in non-blocking mode.
            server.startSuspend(wait = false)

            // Now, suspend this method until the _serverStatus flow confirms server is Started (or Error).
            withTimeout(SERVER_STARTUP_TIMEOUT_MILLIS) {
                _serverStatus
                    .filter { it is ServerStatus.Started || it is ServerStatus.Error }
                    .first() // Suspend until either Started or Error is emitted
                    .also { status ->
                        if (status is ServerStatus.Error) {
                            // If an error occurred during startup, propagate it as an exception.
                            _serverInfo = null // Ensure _serverInfo is null if startup failed
                            throw status.error
                        }
                        // If it's ServerStatus.Started, _serverInfo has already been updated.
                    }
            }
            logger.info("ServerControlService.startSuspend() completed, server is fully ready.")

        } catch (e: TimeoutCancellationException) {
            // Specific handling for startup timeout
            logger.error("Server startup timed out after $SERVER_STARTUP_TIMEOUT_MILLIS ms.", e)
            _serverStatus.value = ServerStatus.Error(e) // Update status to reflect timeout
            server.stopSuspend(100, 3000) // Attempt graceful shutdown of the partially started server
            throw IllegalStateException("Server startup timed out.", e) // Re-throw a clear exception
        } catch (e: Exception) {
            // General exception during Ktor engine startup
            logger.error("Error during Ktor engine startup.", e)
            _serverStatus.value = ServerStatus.Error(e) // Update status to reflect error
            server.stopSuspend(100, 3000) // Attempt graceful shutdown
            throw e // Re-throw the original exception
        }
    }

    /**
     * Stops the running server instance gracefully.
     * If no server is running, a warning is logged.
     *
     * @param gracePeriodMillis The maximum amount of time for activity to cool down before forceful shutdown.
     * @param timeoutMillis The total maximum amount of time to wait until a server stops.
     */
    override suspend fun stopSuspend(gracePeriodMillis: Long, timeoutMillis: Long) {
        val instance = internalServerInstance
        if (instance != null) {
            logger.info("Stopping Ktor engine...")
            instance.stopSuspend(gracePeriodMillis, timeoutMillis)
            internalServerInstance = null // Clear reference
            _serverInfo = null // Clear server info
        } else {
            logger.warn("Attempted to stop server, but no server instance is currently running.")
        }
    }

    /**
     * Returns information about the currently running server instance.
     * This will be non-null only when the server's status is [ServerStatus.Started].
     *
     * @return [ServerInstanceInfo] with current server details, or null if the server is not running or failed to start.
     */
    override fun getServerInfo(): ServerInstanceInfo? {
        return _serverInfo
    }

    /**
     * Creates an instance of the embedded Ktor server configured with the Chatbot module and connectors.
     * This function constructs the engine instance but does not start it.
     *
     * @param factory The Ktor engine factory to use (e.g., [Jetty]).
     * @param port The desired port. If 0, a random available port will be used when started.
     * @param host The host to bind connectors to.
     * @param onStarting Callback invoked when Ktor's application monitor signals starting.
     * @param onStarted Callback invoked when Ktor's application monitor signals started.
     * @param onStopping Callback invoked when Ktor's application monitor signals stopping.
     * @param onStopped Callback invoked when Ktor's application monitor signals stopped.
     * @return An [EmbeddedServer] instance, configured and ready to be started.
     */
    private fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> createEmbeddedServer(
        factory: ApplicationEngineFactory<TEngine, TConfiguration>,
        port: Int,
        host: String,
        onStarting: (Application) -> Unit,
        onStarted: (Application) -> Unit,
        onStopping: (Application) -> Unit,
        onStopped: (Application) -> Unit,
    ): EmbeddedServer<TEngine, TConfiguration> {
        val currentSslConfig = sslConfig

        return embeddedServer(
            factory = factory,
            configure = {
                // Configure HTTPS connector if connector type requires it
                if (networkConfig.connectorType == ServerConnectorType.HTTPS ||
                    networkConfig.connectorType == ServerConnectorType.HTTP_AND_HTTPS
                ) {
                    val ssl = requireNotNull(currentSslConfig) {
                        "SSL configuration is mandatory for connectorType ${networkConfig.connectorType}"
                    }
                    val certManager = requireNotNull(certificateManager) {
                        "Certificate manager is mandatory for connectorType ${networkConfig.connectorType}"
                    }
                    sslConnector(
                        keyStore = certManager.loadCertificateFromKeystore(),
                        keyAlias = ssl.keyAlias,
                        keyStorePassword = { ssl.keystorePassword.toCharArray() },
                        privateKeyPassword = { ssl.keyPassword.toCharArray() }
                    ) {
                        this.port = ssl.port
                        this.host = host
                    }
                }

                // Configure HTTP connector if connector type requires it
                if (networkConfig.connectorType == ServerConnectorType.HTTP ||
                    networkConfig.connectorType == ServerConnectorType.HTTP_AND_HTTPS
                ) {
                    connector {
                        this.port = port
                        this.host = host
                    }
                }
            }
        ) {
            // Subscribe to Ktor's application lifecycle events
            this.monitor.subscribe(ApplicationStarting) { onStarting(it) }
            this.monitor.subscribe(ApplicationStarted) { onStarted(it) }
            this.monitor.subscribe(ApplicationStopping) { onStopping(it) }
            this.monitor.subscribe(ApplicationStopped) { onStopped(it) }

            // Apply the main application module
            chatBotServerModule(databaseConfig, encryptionConfig, jwtConfig, corsConfig)
        }
    }
}