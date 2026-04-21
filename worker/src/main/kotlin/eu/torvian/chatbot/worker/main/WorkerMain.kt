package eu.torvian.chatbot.worker.main

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.worker.config.DefaultWorkerConfigLoader
import eu.torvian.chatbot.worker.config.WorkerConfigLoader
import eu.torvian.chatbot.worker.config.WorkerAppConfigDto
import eu.torvian.chatbot.worker.config.WorkerRuntimeConfig
import eu.torvian.chatbot.worker.config.toDomain
import eu.torvian.chatbot.worker.koin.workerModule
import eu.torvian.chatbot.worker.runtime.WorkerRuntime
import eu.torvian.chatbot.worker.setup.DefaultWorkerSetupManager
import eu.torvian.chatbot.worker.setup.WorkerSetupManager
import eu.torvian.chatbot.worker.setup.WorkerSecrets
import io.ktor.client.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import org.apache.logging.log4j.Logger as Log4jLogger

/**
 * Standalone worker process entrypoint.
 *
 * The worker loads its layered config directory, boots DI, and delegates runtime orchestration
 * to [WorkerRuntime]. Supports three execution modes:
 * - Normal runtime: loads config and runs indefinitely
 * - Setup mode: runs interactive setup flow to configure the worker
 * - Single-run mode: runs one job and exits
 *
 * @property configLoader Loader used for reading and writing layered config files.
 * @property setupManagerFactory Factory used to create the setup manager; overridden by tests.
 */
class WorkerMain(
    private val configLoader: WorkerConfigLoader = DefaultWorkerConfigLoader(),
    private val setupManagerFactory: () -> WorkerSetupManager = { DefaultWorkerSetupManager(configLoader) }
) {
    companion object {
        /**
         * JVM entrypoint used by the Java launcher.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            WorkerMain().start(args)
        }
    }

    /**
     * Logger used for worker startup/runtime lifecycle logs.
     */
    private val logger: Log4jLogger = LogManager.getLogger(WorkerMain::class.java)

    /**
     * JSON codec used for parsing setup-generated worker secrets.
     */
    private val workerJson = Json {
        ignoreUnknownKeys = true
    }

    /**
     * Boots the worker process for this instance.
     *
     * Entry point for the standalone worker application. Parses command-line arguments,
     * loads configuration, optionally runs setup, initializes the DI container, and
     * executes the worker runtime. On any startup error, logs the error and exits with status 1.
     *
     * @param args Command-line arguments. Supports:
     *   - `--config=<config-dir>` Override config directory path
     *   - `--setup` Run interactive setup flow and exit
     *   - `--once` Run a single job and exit instead of infinite runtime
     *   - `--server-url=<url>` Override server base URL during setup (use with --setup only)
     * @return Does not return normally; exits the process with status 1 on startup failure
     *         or status 0 on clean completion.
     */
    fun start(args: Array<String>) = runBlocking {
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
     *
     * Orchestrates the full worker startup pipeline:
     * 1. Parses CLI arguments
     * 2. Loads configuration from layered config directory
     * 3. Optionally runs interactive setup flow
     * 4. Validates configuration and creates domain model
     * 5. Reads private key for TLS
     * 6. Boots Koin DI container
     * 7. Initializes HTTP client and runs worker runtime
     *
     * @param args Command-line arguments passed to the worker process.
     * @return Either a logical startup/runtime error or `Unit` on clean completion.
     */
    suspend fun run(args: Array<String>): Either<WorkerMainError, Unit> = either {
        val options = WorkerCliParser.parse(args)
        ensure(!(!options.setup && options.serverUrlOverride != null)) {
            WorkerMainError.InvalidArguments(
                "--server-url can only be used together with --setup"
            )
        }

        val configDir = configLoader.resolveConfigDir(options.configPathOverride)
        logger.info("Resolved worker config directory: {}", configDir)

        // Phase 1: Load the DTO (nullable/partial).
        var currentDto = configLoader.loadAppConfigDto(configDir)
            .mapLeft { WorkerMainError.Config(it) }
            .bind()
        logger.info("Initial worker configuration DTO loaded.")

        // Phase 2: Setup (explicit via --setup, or automatic when setup.required=true).
        if (options.setup || currentDto.setup?.required == true) {
            runWorkerSetup(configDir, currentDto, options.serverUrlOverride).bind()

            if (options.setup) {
                return@either
            }

            currentDto = configLoader.loadAppConfigDto(configDir)
                .mapLeft { WorkerMainError.Config(it) }
                .bind()
        }

        // Phase 3: Strict assembly and validation.
        val appConfig = currentDto.toDomain()
            .mapLeft { WorkerMainError.Config(it) }
            .bind()
        val config = appConfig.worker

        val privateKeyPem = readPrivateKeyPem(configDir, config).bind()
        val tokenPath = resolvePath(configDir, config.tokenFilePath)

        val koinApplication = startKoin {
            modules(workerModule(config, tokenPath, privateKeyPem))
        }
        val koin = koinApplication.koin
        val httpClient = koin.get<HttpClient>()
        val runtime = koin.get<WorkerRuntime>()
        try {
            logger.info("Worker HTTP client initialized for {}", config.serverBaseUrl)
            runtime.run(options.runOnce).mapLeft { WorkerMainError.Runtime(it) }.bind()
        } finally {
            runtime.close()
            httpClient.close()
            stopKoin()
        }
    }

    /**
     * Runs the interactive worker setup flow.
     *
     * Delegates to [DefaultWorkerSetupManager] to guide the operator through configuring
     * the worker, including establishing secure channel to the server and storing secrets.
     * On success, updates the configuration files in the config directory.
     *
     * @param configDir Worker config directory to save setup configuration to.
     * @param serverUrlOverride Optional override of server URL.
     * @return Either a setup error or `Unit` when setup completed.
     */
    private suspend fun runWorkerSetup(
        configDir: Path,
        mergedConfig: WorkerAppConfigDto,
        serverUrlOverride: String?
    ): Either<WorkerMainError, Unit> {
        logger.info("Running worker setup...")
        val setupManager = setupManagerFactory()
        return setupManager.run(configDir, mergedConfig, serverUrlOverride)
            .mapLeft { WorkerMainError.Setup(it) }
    }

    /**
     * Loads worker private-key material from the setup-generated secrets file.
     *
     * Reads the secrets file that was created during setup to extract the worker's
     * private key needed for TLS operations.
     *
     * @param configDir Resolved worker config directory path.
     * @param config Parsed runtime configuration containing path to secrets file.
     * @return Either a startup error or the PEM-encoded private key text.
     */
    private suspend fun readPrivateKeyPem(
        configDir: Path,
        config: WorkerRuntimeConfig
    ): Either<WorkerMainError, String> =
        either {
            val secretsJsonPath = resolvePath(configDir, config.secretsJsonPath)
            val secrets = readSecretsFile(secretsJsonPath).mapLeft {
                WorkerMainError.SecretsReadFailed(
                    secretsJsonPath.toString(),
                    it.reason
                )
            }.bind()
            secrets.privateKeyPem
        }

    /**
     * Reads the setup-generated secrets payload from disk.
     *
     * Deserializes the JSON secrets file created by the setup flow, which contains
     * authentication material and other sensitive configuration.
     *
     * @param path Secrets file path to read.
     * @return Either a secrets-read error or the parsed WorkerSecrets payload.
     */
    private suspend fun readSecretsFile(path: Path): Either<WorkerMainError.SecretsReadFailed, WorkerSecrets> =
        withContext(Dispatchers.IO) {
            try {
                if (!Files.exists(path)) {
                    WorkerMainError.SecretsReadFailed(path.toString(), "file not found").left()
                } else {
                    val secrets = workerJson.decodeFromString<WorkerSecrets>(Files.readString(path))
                    secrets.right()
                }
            } catch (e: SerializationException) {
                WorkerMainError.SecretsReadFailed(path.toString(), e.message ?: "Invalid secrets JSON").left()
            } catch (e: Exception) {
                WorkerMainError.SecretsReadFailed(path.toString(), e.message ?: e::class.simpleName.orEmpty()).left()
            }
        }

    /**
     * Resolves a potentially relative path against the resolved config directory.
     *
     * @param configDir Path of the active worker config directory.
     * @param configuredPath Path value read from config.
     * @return Absolute path if the configured path was already absolute, otherwise a path
     * resolved relative to the config directory.
     */
    private fun resolvePath(configDir: Path, configuredPath: String): Path {
        val targetPath = Path(configuredPath)
        return if (targetPath.isAbsolute) {
            targetPath
        } else {
            configDir.resolve(targetPath).normalize()
        }
    }

}
