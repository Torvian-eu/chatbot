package eu.torvian.chatbot.app.service.mcp

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.*
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Default implementation of LocalMCPServerProcessManager.
 *
 * Provides low-level process management operations with the following features:
 * - Starting processes with STDIO communication
 * - Stopping processes gracefully (with forceful fallback)
 * - Querying process status
 * - Tracking active processes
 *
 * Design principles:
 * - Pure process management (no MCP SDK knowledge)
 * - Stateless operations (config passed as parameter)
 * - Thread-safe process tracking using ConcurrentHashMap for high concurrency
 * - Comprehensive error handling via Either
 * - Proper resource cleanup
 *
 * This component is called by MCPClientService (US2.3), which wraps the MCP SDK
 * for higher-level operations like tool discovery and execution.
 *
 * @property clock Clock instance for generating timestamps (injectable for testing)
 */
class LocalMCPServerProcessManagerDesktop(
    private val clock: Clock = Clock.System
) : LocalMCPServerProcessManager {
    companion object {
        private val logger = kmpLogger<LocalMCPServerProcessManagerDesktop>()
        private const val GRACEFUL_SHUTDOWN_TIMEOUT_MS = 5000L
        private const val FORCE_SHUTDOWN_TIMEOUT_MS = 2000L
    }

    private val processes = ConcurrentHashMap<Long, ManagedProcess>()

    /**
     * Starts an MCP server process.
     *
     * This method:
     * 1. Validates the configuration
     * 2. Optimistically starts the process without a lock to allow for concurrent startups.
     * 3. Atomically registers the new process using a compare-and-set loop to handle race conditions.
     *
     * @param config The MCP server configuration
     * @return Either.Right with Unit on success, or Either.Left with StartServerError on failure
     */
    override suspend fun startServer(config: LocalMCPServer): Either<StartServerError, ProcessStatus> {
        logger.info("Starting MCP server: ${config.name} (ID: ${config.id})")

        // Validate configuration
        validateConfiguration(config).onLeft { error ->
            logger.error("Invalid configuration for server ${config.id}: ${error.message}")
            return error.left()
        }

        // Quick check to fail fast if a process is already alive.
        processes[config.id]?.let {
            if (it.isAlive) {
                logger.warn("MCP server ${config.id} is already running")
                return StartServerError.ProcessAlreadyRunning(config.id).left()
            }
        }

        // Build command and start the process optimistically. This is the long-running part.
        val command = buildCommand(config)
        logger.debug("Command for server ${config.id}: ${command.joinToString(" ")}")
        val process: Process
        try {
            process = withContext(Dispatchers.IO) {
                val processBuilder = ProcessBuilder(command)

                if (config.environmentVariables.isNotEmpty()) {
                    val environment = processBuilder.environment()
                    config.environmentVariables.forEach { (key, value) -> environment[key] = value }
                    logger.debug("Set ${config.environmentVariables.size} environment variables for server ${config.id}")
                }

                config.workingDirectory?.let { workingDir ->
                    val dir = File(workingDir)
                    if (dir.exists() && dir.isDirectory) {
                        processBuilder.directory(dir)
                        logger.debug("Set working directory to: $workingDir")
                    } else {
                        logger.warn("Working directory does not exist: $workingDir - using default")
                    }
                }

                processBuilder.start()
            }
        } catch (e: IOException) {
            val error = StartServerError.ProcessStartFailed(
                config.id,
                command.joinToString(" "),
                "IO error: ${e.message}",
                e
            )
            logger.error("Failed to start MCP server ${config.id}: ${error.message}", e)
            return error.left()
        } catch (e: SecurityException) {
            val error = StartServerError.ProcessStartFailed(
                config.id,
                command.joinToString(" "),
                "Security error: ${e.message}",
                e
            )
            logger.error("Security error starting MCP server ${config.id}: ${error.message}", e)
            return error.left()
        } catch (e: Exception) {
            val error = StartServerError.UnexpectedError.from(e)
            logger.error("Unexpected error starting MCP server ${config.id}: ${error.message}", e)
            return error.left()
        }

        val newManagedProcess = ManagedProcess(
            serverId = config.id,
            process = process,
            pid = process.pid(),
            startedAt = clock.now(),
            command = command
        )

        // Atomically update the map using a compare-and-set (CAS) loop.
        while (true) {
            val oldProcess = processes[config.id]

            if (oldProcess == null) {
                // Slot is empty, try to insert our new process.
                if (processes.putIfAbsent(config.id, newManagedProcess) == null) {
                    logger.info("Successfully started and registered MCP server ${config.id} with PID ${newManagedProcess.pid}")
                    return ProcessStatus(
                        serverId = config.id,
                        state = ProcessState.RUNNING,
                        pid = newManagedProcess.pid,
                        startedAt = newManagedProcess.startedAt,
                    ).right()
                }
                // Lost race: another thread inserted a process. Loop again to re-evaluate.
                continue
            }

            if (oldProcess.isAlive) {
                // A live process is already running. We started one unnecessarily.
                logger.warn("MCP server ${config.id} is already running (race condition detected). Stopping redundant new process.")
                withContext(Dispatchers.IO) { newManagedProcess.process.destroyForcibly() } // Clean up our zombie process
                return StartServerError.ProcessAlreadyRunning(config.id).left()
            } else {
                // A dead process is in the slot. Try to replace it with our new one.
                if (processes.replace(config.id, oldProcess, newManagedProcess)) {
                    logger.info("Successfully started MCP server ${config.id} with PID ${newManagedProcess.pid} (replaced dead process)")
                    return ProcessStatus(
                        serverId = config.id,
                        state = ProcessState.RUNNING,
                        pid = newManagedProcess.pid,
                        startedAt = newManagedProcess.startedAt,
                    ).right()
                }
                // Lost race: another thread replaced or removed it. Loop again to re-evaluate.
                continue
            }
        }
    }

    override suspend fun stopServer(serverId: Long): Either<StopServerError, Unit> {
        logger.info("Stopping MCP server: $serverId")

        val managedProcess = processes.remove(serverId)
            ?: return StopServerError.ProcessNotRunning(serverId).left()

        return performShutdown(managedProcess)
    }

    /**
     * Internal helper to perform the shutdown logic for a given process.
     * This contains the blocking `waitFor` calls and must be run on the IO dispatcher.
     */
    private suspend fun performShutdown(managedProcess: ManagedProcess): Either<StopServerError, Unit> =
        withContext(Dispatchers.IO) {
            val serverId = managedProcess.serverId
            if (!managedProcess.isAlive) {
                logger.info("MCP server $serverId is already stopped")
                return@withContext Unit.right()
            }

            try {
                logger.debug("Attempting graceful shutdown of MCP server $serverId")
                managedProcess.process.destroy()
                if (managedProcess.process.waitFor(GRACEFUL_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    logger.info("MCP server $serverId stopped gracefully")
                    return@withContext Unit.right()
                }

                logger.warn("MCP server $serverId did not stop gracefully, forcing shutdown")
                managedProcess.process.destroyForcibly()
                if (managedProcess.process.waitFor(FORCE_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    logger.info("MCP server $serverId stopped forcefully")
                    return@withContext Unit.right()
                }

                val error =
                    StopServerError.ProcessStopFailed(serverId, "Process did not terminate after forceful shutdown")
                logger.error(error.message)
                error.left()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                val error = StopServerError.ProcessStopFailed(
                    serverId,
                    "Interrupted while waiting for process termination",
                    e
                )
                logger.error("Error stopping MCP server $serverId: ${error.message}", e)
                error.left()
            } catch (e: Exception) {
                val error = StopServerError.UnexpectedError.from(e)
                logger.error("Unexpected error stopping MCP server $serverId: ${error.message}", e)
                error.left()
            }
        }

    override suspend fun getServerStatus(serverId: Long): ProcessStatus = withContext(Dispatchers.IO) {
        val managedProcess = processes[serverId]
            ?: return@withContext ProcessStatus(serverId = serverId, state = ProcessState.STOPPED)

        if (!managedProcess.isAlive) {
            val exitCode = try {
                managedProcess.process.exitValue()
            } catch (_: IllegalThreadStateException) {
                null
            }
            val status = ProcessStatus(
                serverId = serverId,
                state = if (exitCode != null && exitCode != 0) ProcessState.ERROR else ProcessState.STOPPED,
                exitCode = exitCode,
                stoppedAt = clock.now(),
                errorMessage = if (exitCode != null && exitCode != 0) "Process exited with code $exitCode" else null
            )
            // Atomically remove the dead process entry to avoid race conditions.
            if (processes.remove(serverId, managedProcess)) {
                logger.debug("Cleaned up dead process entry for server $serverId")
            }
            return@withContext status
        }

        return@withContext ProcessStatus(
            serverId = serverId,
            state = ProcessState.RUNNING,
            pid = managedProcess.pid,
            startedAt = managedProcess.startedAt
        )
    }

    override suspend fun restartServer(config: LocalMCPServer): Either<RestartServerError, ProcessStatus> {
        logger.info("Restarting MCP server: ${config.name} (ID: ${config.id})")

        // Stop the server first (ignore ProcessNotRunning error)
        stopServer(config.id).onLeft { error ->
            if (error !is StopServerError.ProcessNotRunning) {
                return RestartServerError.StopFailed(error).left()
            }
        }

        // Start the server
        return startServer(config).mapLeft { error ->
            RestartServerError.StartFailed(error)
        }
    }

    override suspend fun stopAllServers(): Int {
        logger.info("Stopping all MCP server processes")

        val processesToStop = processes.keys.mapNotNull { key ->
            processes.remove(key)
        }

        var stoppedCount = 0
        processesToStop.forEach { managedProcess ->
            if (performShutdown(managedProcess).isRight()) {
                stoppedCount++
            }
        }

        logger.info("Stopped $stoppedCount MCP server processes")
        return stoppedCount
    }

    override fun getProcessInputStream(serverId: Long): Source? {
        return processes[serverId]?.process?.inputStream?.asSource()?.buffered()
    }

    override fun getProcessOutputStream(serverId: Long): Sink? {
        return processes[serverId]?.process?.outputStream?.asSink()?.buffered()
    }

    override fun getProcessErrorStream(serverId: Long): Source? {
        return processes[serverId]?.process?.errorStream?.asSource()?.buffered()
    }

    override fun close() {
        runBlocking { stopAllServers() }
    }

    // Private helper methods

    /**
     * Validates the MCP server configuration to ensure it's valid before starting a process.
     *
     * This method checks:
     * - Command is not blank
     * - If the command appears to be a file path, the file exists and is executable
     *
     * @param config The MCP server configuration to validate
     * @return Either.Right with Unit if validation succeeds, or Either.Left with StartServerError if validation fails
     */
    private fun validateConfiguration(config: LocalMCPServer): Either<StartServerError, Unit> {
        if (config.command.isBlank()) {
            return StartServerError.InvalidConfiguration(config.id, "Command cannot be blank").left()
        }
        if (config.command.contains(File.separator) || config.command.contains("/")) {
            val commandFile = File(config.command)
            if (!commandFile.exists()) {
                return StartServerError.InvalidConfiguration(
                    config.id,
                    "Command file does not exist: ${config.command}"
                ).left()
            }
            if (!commandFile.canExecute()) {
                return StartServerError.InvalidConfiguration(
                    config.id,
                    "Command file is not executable: ${config.command}"
                ).left()
            }
        }
        return Unit.right()
    }

    /**
     * Builds the complete command line from the configuration.
     *
     * Combines the base command with any arguments specified in the configuration.
     *
     * @param config The MCP server configuration
     * @return A list representing the complete command and its arguments
     */
    private fun buildCommand(config: LocalMCPServer): List<String> {
        return listOf(config.command) + config.arguments
    }

    /**
     * Represents a managed MCP server process with metadata.
     *
     * @property serverId The logical ID of the MCP server (from LocalMCPServer)
     * @property process The Java Process object for process control
     * @property pid The operating system process ID (cached at creation time)
     * @property startedAt Timestamp when the process was started (for monitoring)
     * @property command The original command and arguments used to start the process (for logging/debugging)
     */
    private data class ManagedProcess(
        val serverId: Long,
        val process: Process,
        val pid: Long,
        val startedAt: Instant,
        val command: List<String>
    ) {
        /**
         * Checks if the process is currently alive.
         *
         * This is a convenience property that delegates to Process.isAlive() for readability.
         */
        val isAlive: Boolean get() = process.isAlive
    }
}
