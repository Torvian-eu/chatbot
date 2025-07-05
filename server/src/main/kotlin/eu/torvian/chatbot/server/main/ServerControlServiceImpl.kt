package eu.torvian.chatbot.server.main

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [ServerControlService] that manages the lifecycle of a Ktor server.
 *
 * @property serverConfig The configuration for the server.
 */
class ServerControlServiceImpl(val serverConfig: ServerConfig) : ServerControlService {
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

        val server = createEmbeddedServer(
            Netty, port = serverConfig.port, host = serverConfig.host,
            onStarting = {
                logger.info("Ktor server starting...")
                _serverStatus.value = ServerStatus.Starting
            },
            onStarted = {
                logger.info("Ktor server started.")
                CoroutineScope(Dispatchers.IO).launch {
                    val actualPort = it.engine.resolvedConnectors().firstOrNull()?.port
                    if (actualPort == null) {
                        logger.error("Failed to determine port after server start. Aborting.")
                        _serverStatus.value =
                            ServerStatus.Error(IllegalStateException("Failed to determine port after server start."))
                        return@launch
                    }
                    val info = ServerInstanceInfo(
                        scheme = serverConfig.scheme,
                        host = serverConfig.host,
                        port = actualPort,
                        path = serverConfig.path,
                        startTime = Clock.System.now()
                    )
                    serverInfo = info
                    logger.info("Ktor server started successfully on uri: ${info.baseUri}")
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
     * @param factory The engine factory to use (e.g., [Netty]).
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
        return embeddedServer(
            factory = factory,
            port = port,
            host = host
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