package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.server.service.security.CertificateManager
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.jakarta.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File

/**
 * Implementation of [ServerControlService] that manages the lifecycle of a Ktor server.
 *
 * @property serverConfig The configuration for the server.
 * @property certificateManager Manager for SSL certificate operations.
 */
class ServerControlServiceImpl(
    val serverConfig: ServerConfig,
    private val certificateManager: CertificateManager
) : ServerControlService {
    private val logger: Logger = LogManager.getLogger(this::class.java)
    private var serverInstance: EmbeddedServer<*, *>? = null
    private var serverInfo: ServerInstanceInfo? = null

    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.NotStarted)
    override val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    override suspend fun startSuspend() {
        // Prevent starting multiple times if already starting or started
        if (_serverStatus.value !is ServerStatus.NotStarted && _serverStatus.value !is ServerStatus.Stopped
            && _serverStatus.value !is ServerStatus.Error
        ) {
            logger.warn("Server start requested, but status is ${_serverStatus.value}. Ignoring.")
            return
        }

        logger.info("Starting Chatbot Server...")

        // Generate self-signed certificate if needed
        serverConfig.sslConfig?.let {
            if (it.generateSelfSigned && !File(it.keystorePath).exists()) {
                logger.info("Generating self-signed server certificate at ${it.keystorePath}...")
                certificateManager.generateServerCertificate()
            }
        }

        val server = createEmbeddedServer(
            Jetty, port = serverConfig.port, host = serverConfig.host,
            onStarting = {
                logger.info("Ktor server starting...")
                _serverStatus.value = ServerStatus.Starting
            },
            onStarted = {
                logger.info("Ktor server started.")
                CoroutineScope(Dispatchers.IO).launch {
                    val connectors = it.engine.resolvedConnectors()
                    if (connectors.isEmpty()) {
                        logger.error("Failed to determine any active connectors after server start. Aborting.")
                        _serverStatus.value =
                            ServerStatus.Error(IllegalStateException("Failed to determine any active connectors after server start."))
                        return@launch
                    }

                    // Log all active connectors
                    val connectorInfo = connectors.joinToString { c ->
                        val scheme = if (c.type == ConnectorType.HTTPS) "https" else "http"
                        "$scheme://${c.host}:${c.port}"
                    }
                    logger.info("Ktor server listening on: $connectorInfo")

                    // Determine the primary connector for ServerInstanceInfo
                    // The primary is HTTPS if available, otherwise HTTP.
                    val primaryConnector = connectors.find { c -> c.type == ConnectorType.HTTPS } ?: connectors.first()

                    val info = ServerInstanceInfo(
                        scheme = if (primaryConnector.type == ConnectorType.HTTPS) "https" else "http",
                        host = primaryConnector.host,
                        port = primaryConnector.port,
                        path = serverConfig.path,
                        startTime = Clock.System.now()
                    )
                    serverInfo = info
                    logger.info("Primary server URI set to: ${info.baseUri}")
                    _serverStatus.value = ServerStatus.Started(info)
                }
            },
            onStopping = {
                logger.info("Ktor server stopping...")
                _serverStatus.value = ServerStatus.Stopping
            },
            onStopped = {
                logger.info("Ktor server stopped gracefully.")
                serverInstance = null
                serverInfo = null
                _serverStatus.value = ServerStatus.Stopped
            },
        )
        // Store the server instance
        serverInstance = server

        // Start the server
        try {
            server.startSuspend(wait = false)
        } catch (e: Exception) {
            logger.error("Error starting Ktor server.", e)
            _serverStatus.value = ServerStatus.Error(e)
        }
    }

    override suspend fun stopSuspend(gracePeriodMillis: Long, timeoutMillis: Long) {
        val instance = serverInstance
        if (instance != null) {
            logger.info("Stopping Ktor server...")
            instance.stopSuspend(gracePeriodMillis, timeoutMillis)

            // Clear references
            serverInstance = null
            serverInfo = null
        } else {
            logger.warn("Attempted to stop server, but no server instance is running.")
        }
        return
    }

    override fun getServerInfo(): ServerInstanceInfo? {
        return serverInfo
    }

    /**
     * Creates an instance of the embedded Ktor server configured with the Chatbot module.
     * This function creates the engine instance but does not start it.
     *
     * @param factory The engine factory to use (e.g., [Jetty]).
     * @param port The desired port. If 0, a random available port will be used when started.
     * @param host The host to bind to.
     * @param onStarting A callback to be invoked when the server is starting.
     * @param onStarted A callback to be invoked when the server has started.
     * @param onStopping A callback to be invoked when the server is stopping.
     * @param onStopped A callback to be invoked when the server has stopped.
     * @return An instance of [EmbeddedServer] ready to be started.
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
        val currentSslConfig = this.serverConfig.sslConfig

        return embeddedServer(
            factory = factory,
            configure = {
                val isSslEnabled = currentSslConfig?.enabled == true

                // Configure HTTPS connector if SSL is enabled
                if (isSslEnabled) {
                    sslConnector(
                        keyStore = certificateManager.loadCertificateFromKeystore(),
                        keyAlias = currentSslConfig.keyAlias,
                        keyStorePassword = { currentSslConfig.keystorePassword.toCharArray() },
                        privateKeyPassword = { currentSslConfig.keyPassword.toCharArray() }
                    ) {
                        this.port = currentSslConfig.port
                        this.host = host
                    }
                }

                // Configure HTTP connector if SSL is disabled OR if both are allowed
                if (!isSslEnabled || serverConfig.allowHttpAndHttps) {
                    connector {
                        this.port = port
                        this.host = host
                    }
                }
            }
        ) {
            this.monitor.subscribe(ApplicationStarting) {
                onStarting(it)
            }
            this.monitor.subscribe(ApplicationStarted) {
                onStarted(it)
            }
            this.monitor.subscribe(ApplicationStopping) {
                onStopping(it)
            }
            this.monitor.subscribe(ApplicationStopped) {
                onStopped(it)
            }

            chatBotServerModule() // Apply the reusable module configuration
        }
    }
}