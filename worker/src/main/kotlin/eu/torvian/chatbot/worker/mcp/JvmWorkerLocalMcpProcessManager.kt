package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * JVM process-manager implementation for worker-local MCP server runtime.
 *
 * This class owns only operating-system process lifecycle concerns and remains independent from
 * protocol orchestration and persistence responsibilities.
 *
 * @property clock Time source used for status snapshots.
 */
class JvmWorkerLocalMcpProcessManager(
    private val clock: Clock = Clock.System
) : WorkerLocalMcpProcessManager {
    /**
     * Graceful-shutdown timeout before forceful termination fallback.
     */
    private val gracefulShutdownTimeoutMillis: Long = 5_000L

    /**
     * Forceful-shutdown timeout before reporting failure.
     */
    private val forceShutdownTimeoutMillis: Long = 2_000L

    /**
     * In-memory process registry keyed by server identifier.
     */
    private val processByServerId: ConcurrentHashMap<Long, ManagedProcess> = ConcurrentHashMap()

    override suspend fun startServer(
        config: LocalMCPServerDto
    ): Either<WorkerLocalMcpStartProcessError, WorkerLocalMcpProcessStatus> {
        validateConfiguration(config).onLeft { validationError ->
            return validationError.left()
        }

        processByServerId[config.id]?.let { existingProcess ->
            if (existingProcess.isAlive) {
                return WorkerLocalMcpStartProcessError.ProcessAlreadyRunning(config.id).left()
            }
        }

        val command = buildCommand(config)
        val process = try {
            startProcess(config, command)
        } catch (exception: IOException) {
            return WorkerLocalMcpStartProcessError.ProcessStartFailed(
                serverId = config.id,
                command = command.joinToString(" "),
                reason = "IO error: ${exception.message}",
                cause = exception
            ).left()
        } catch (exception: SecurityException) {
            return WorkerLocalMcpStartProcessError.ProcessStartFailed(
                serverId = config.id,
                command = command.joinToString(" "),
                reason = "Security error: ${exception.message}",
                cause = exception
            ).left()
        }

        val managedProcess = ManagedProcess(
            serverId = config.id,
            process = process,
            pid = process.pid(),
            startedAt = clock.now()
        )

        while (true) {
            val existingProcess = processByServerId[config.id]
            if (existingProcess == null) {
                if (processByServerId.putIfAbsent(config.id, managedProcess) == null) {
                    return WorkerLocalMcpProcessStatus(
                        serverId = config.id,
                        state = WorkerLocalMcpProcessState.RUNNING,
                        pid = managedProcess.pid,
                        startedAt = managedProcess.startedAt
                    ).right()
                }
                continue
            }

            if (existingProcess.isAlive) {
                withContext(Dispatchers.IO) {
                    managedProcess.process.destroyForcibly()
                }
                return WorkerLocalMcpStartProcessError.ProcessAlreadyRunning(config.id).left()
            }

            if (processByServerId.replace(config.id, existingProcess, managedProcess)) {
                return WorkerLocalMcpProcessStatus(
                    serverId = config.id,
                    state = WorkerLocalMcpProcessState.RUNNING,
                    pid = managedProcess.pid,
                    startedAt = managedProcess.startedAt
                ).right()
            }
        }
    }

    override suspend fun stopServer(serverId: Long): Either<WorkerLocalMcpStopProcessError, Unit> {
        val managedProcess = processByServerId.remove(serverId)
            ?: return WorkerLocalMcpStopProcessError.ProcessNotRunning(serverId).left()
        return stopManagedProcess(managedProcess)
    }

    override suspend fun getServerStatus(serverId: Long): WorkerLocalMcpProcessStatus = withContext(Dispatchers.IO) {
        val managedProcess = processByServerId[serverId]
            ?: return@withContext WorkerLocalMcpProcessStatus(
                serverId = serverId,
                state = WorkerLocalMcpProcessState.STOPPED
            )

        if (!managedProcess.isAlive) {
            val exitCode = try {
                managedProcess.process.exitValue()
            } catch (_: IllegalThreadStateException) {
                null
            }
            processByServerId.remove(serverId, managedProcess)
            return@withContext WorkerLocalMcpProcessStatus(
                serverId = serverId,
                state = if (exitCode != null && exitCode != 0) {
                    WorkerLocalMcpProcessState.ERROR
                } else {
                    WorkerLocalMcpProcessState.STOPPED
                },
                exitCode = exitCode,
                stoppedAt = clock.now(),
                errorMessage = if (exitCode != null && exitCode != 0) "Process exited with code $exitCode" else null
            )
        }

        WorkerLocalMcpProcessStatus(
            serverId = serverId,
            state = WorkerLocalMcpProcessState.RUNNING,
            pid = managedProcess.pid,
            startedAt = managedProcess.startedAt
        )
    }

    override fun getProcessInputStream(serverId: Long) =
        processByServerId[serverId]?.process?.inputStream?.asSource()?.buffered()

    override fun getProcessOutputStream(serverId: Long) =
        processByServerId[serverId]?.process?.outputStream?.asSink()?.buffered()

    override fun getProcessErrorStream(serverId: Long) =
        processByServerId[serverId]?.process?.errorStream?.asSource()?.buffered()

    override suspend fun close() {
        val managedProcesses = processByServerId.keys.mapNotNull { serverId ->
            processByServerId.remove(serverId)
        }
        managedProcesses.forEach { managedProcess ->
            stopManagedProcess(managedProcess)
        }
    }

    /**
     * Starts the underlying operating-system process.
     *
     * @param config Resolved local MCP server configuration.
     * @param command Full command line.
     * @return Started Java process.
     */
    private suspend fun startProcess(
        config: LocalMCPServerDto,
        command: List<String>
    ): Process = withContext(Dispatchers.IO) {
        val processBuilder = ProcessBuilder(command)
        val runtimeEnvironmentVariables = config.environmentVariables + config.secretEnvironmentVariables
        runtimeEnvironmentVariables.forEach { variable ->
            processBuilder.environment()[variable.key] = variable.value
        }
        config.workingDirectory?.let { configuredDirectory ->
            val directory = File(configuredDirectory)
            if (directory.exists() && directory.isDirectory) {
                processBuilder.directory(directory)
            }
        }
        processBuilder.start()
    }

    /**
     * Stops one managed process with graceful then forceful fallback.
     *
     * @param managedProcess Managed process entry.
     * @return Either stop failure or Unit.
     */
    private suspend fun stopManagedProcess(
        managedProcess: ManagedProcess
    ): Either<WorkerLocalMcpStopProcessError, Unit> = withContext(Dispatchers.IO) {
        if (!managedProcess.isAlive) {
            return@withContext Unit.right()
        }

        try {
            managedProcess.process.destroy()
            if (managedProcess.process.waitFor(gracefulShutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                return@withContext Unit.right()
            }

            managedProcess.process.destroyForcibly()
            if (managedProcess.process.waitFor(forceShutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                return@withContext Unit.right()
            }

            WorkerLocalMcpStopProcessError.ProcessStopFailed(
                serverId = managedProcess.serverId,
                reason = "Process did not terminate after forceful shutdown"
            ).left()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            WorkerLocalMcpStopProcessError.ProcessStopFailed(
                serverId = managedProcess.serverId,
                reason = "Interrupted while waiting for process termination",
                cause = exception
            ).left()
        } catch (exception: Exception) {
            WorkerLocalMcpStopProcessError.ProcessStopFailed(
                serverId = managedProcess.serverId,
                reason = exception.message ?: "Unknown error",
                cause = exception
            ).left()
        }
    }

    /**
     * Validates process-start configuration fields that must be well-formed.
     *
     * @param config Resolved local MCP server configuration.
     * @return Either validation error or Unit.
     */
    private fun validateConfiguration(
        config: LocalMCPServerDto
    ): Either<WorkerLocalMcpStartProcessError, Unit> {
        if (config.command.isBlank()) {
            return WorkerLocalMcpStartProcessError.InvalidConfiguration(
                serverId = config.id,
                reason = "Command cannot be blank"
            ).left()
        }

        if (config.command.contains(File.separator) || config.command.contains('/')) {
            val commandFile = File(config.command)
            if (!commandFile.exists()) {
                return WorkerLocalMcpStartProcessError.InvalidConfiguration(
                    serverId = config.id,
                    reason = "Command file does not exist: ${config.command}"
                ).left()
            }
            if (!commandFile.canExecute()) {
                return WorkerLocalMcpStartProcessError.InvalidConfiguration(
                    serverId = config.id,
                    reason = "Command file is not executable: ${config.command}"
                ).left()
            }
        }

        return Unit.right()
    }

    /**
     * Builds the command and argument list used by ProcessBuilder.
     *
     * @param config Resolved local MCP server configuration.
     * @return Full command list.
     */
    private fun buildCommand(config: LocalMCPServerDto): List<String> = listOf(config.command) + config.arguments

    /**
     * Internal metadata wrapper for one tracked process.
     *
     * @property serverId Persisted local MCP server identifier.
     * @property process Backing Java process instance.
     * @property pid Process identifier recorded at startup.
     * @property startedAt Startup timestamp.
     */
    private data class ManagedProcess(
        val serverId: Long,
        val process: Process,
        val pid: Long,
        val startedAt: Instant
    ) {
        /**
         * Indicates whether the process is currently alive.
         */
        val isAlive: Boolean get() = process.isAlive
    }
}

