package eu.torvian.chatbot.worker.main

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.worker.config.WorkerConfigLoader
import eu.torvian.chatbot.worker.koin.workerModule
import eu.torvian.chatbot.worker.runtime.WorkerRuntime
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger as Log4jLogger
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Standalone worker process entrypoint.
 *
 * The worker loads its local config, boots DI, and delegates runtime orchestration
 * to [WorkerRuntime].
 */
object WorkerMain {
    private val logger: Log4jLogger = LogManager.getLogger(WorkerMain::class.java)

    /**
     * Boots the worker process.
     *
     * @param args Command-line arguments. Supports `--config=...` and `--once`.
     */
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        logger.info("Worker process starting")
        run(args).fold(
            { error ->
                logger.error("Worker startup failed: {}", error)
                kotlin.system.exitProcess(1)
            },
            { logger.info("Worker process finished cleanly") }
        )
    }

    /**
     * Testable startup runner that performs bootstrap and executes the runtime.
     */
    suspend fun run(args: Array<String>): Either<WorkerMainError, Unit> = either {
        val options = WorkerCliParser.parse(args)
        val configPath = WorkerConfigLoader.resolveConfigPath(options.configPathOverride)
        logger.info("Resolved worker config path: {}", configPath)
        val config = WorkerConfigLoader.load(configPath).mapLeft { WorkerMainError.Config(it) }.bind()

        val privateKeyPath = resolvePath(configPath, config.privateKeyPemPath)
        val tokenPath = resolvePath(configPath, config.tokenFilePath)
        val privateKeyPem = readTextFile(privateKeyPath).bind()

        val koinApplication = startKoin {
            modules(workerModule(config, tokenPath, privateKeyPem))
        }
        val koin = koinApplication.koin
        val httpClient = koin.get<HttpClient>()
        try {
            logger.info("Worker HTTP client initialized for {}", config.serverBaseUrl)
            val runtime = koin.get<WorkerRuntime>()
            runtime.run(options.runOnce).mapLeft { WorkerMainError.Auth(it) }.bind()
        } finally {
            httpClient.close()
            stopKoin()
        }
    }

    /**
     * Resolves a potentially relative path against the resolved config directory.
     *
     * @param configPath Path of the active worker config file.
     * @param configuredPath Path value read from config.
     * @return Absolute path if the configured path was already absolute, otherwise a path
     * resolved relative to the config file directory.
     */
    private fun resolvePath(configPath: Path, configuredPath: String): Path {
        val targetPath = Path(configuredPath)
        return if (targetPath.isAbsolute) {
            targetPath
        } else {
            // Keep portable configs working by resolving file references relative to config.json.
            val parent = configPath.parent ?: Path(".")
            parent.resolve(targetPath).normalize()
        }
    }

    /**
     * Reads a bootstrap file without blocking the caller coroutine context.
     *
     * The helper deliberately returns a logical `WorkerMainError` rather than throwing so the
     * entrypoint can stay inside a single Arrow-based startup pipeline.
     *
     * @param path File path to read.
     * @return File content or a startup error describing the failure.
     */
    private suspend fun readTextFile(path: Path): Either<WorkerMainError, String> =
        withContext(Dispatchers.IO) {
            try {
                if (!Files.exists(path)) {
                    // File absence is a startup error because the worker cannot continue without the key.
                    WorkerMainError.FileReadFailed(path.toString(), "file not found").left()
                } else {
                    Files.readString(path).right()
                }
            } catch (e: Exception) {
                WorkerMainError.FileReadFailed(path.toString(), e.message ?: e::class.simpleName.orEmpty()).left()
            }
        }
}


