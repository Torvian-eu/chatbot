package eu.torvian.chatbot.server.main

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for managing server lifecycle operations.
 * Provides methods to start, stop, and monitor the server.
 */
interface ServerControlService {
    /**
     * Observes the current status of the embedded server.
     */
    val serverStatus: StateFlow<ServerStatus>

    /**
     * Starts the server in non-blocking (suspend) mode.
     * This method suspends until the server is ready to accept connections.
     */
    suspend fun startSuspend()

    /**
     * Stops the running server instance gracefully.
     *
     * @param gracePeriodMillis the maximum amount of time for activity to cool down.
     * @param timeoutMillis the maximum amount of time to wait until a server stops gracefully.
     */
    suspend fun stopSuspend(gracePeriodMillis: Long = 1000, timeoutMillis: Long = 1000)

    /**
     * Returns information about the current server instance.
     *
     * @return [ServerInstanceInfo] with current server details or null if the server is not running.
     */
    fun getServerInfo(): ServerInstanceInfo?
}

